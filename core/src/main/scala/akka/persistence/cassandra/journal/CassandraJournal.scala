/*
 * Copyright (C) 2016-2017 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.cassandra.journal

import java.lang.{ Long => JLong }
import java.nio.ByteBuffer
import java.util.{ UUID, HashMap => JHMap, Map => JMap }

import akka.Done
import akka.actor.SupervisorStrategy.Stop
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.actor.ExtendedActorSystem
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.cassandra.session.scaladsl.CassandraSession
import akka.event.{ Logging, LoggingAdapter }
import akka.persistence._
import akka.persistence.cassandra.EventWithMetaData.UnknownMetaData
import akka.persistence.cassandra._
import akka.persistence.cassandra.journal.TagWriters.{ BulkTagWrite, TagWrite, TagWritersSession }
import akka.persistence.cassandra.query.EventsByPersistenceIdStage.Extractors
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.journal.{ AsyncWriteJournal, Tagged }
import akka.persistence.query.PersistenceQuery
import akka.serialization.{ AsyncSerializer, Serialization, SerializationExtension }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.OptionVal
import com.datastax.oss.driver.api.core.cql._
import com.typesafe.config.Config
import com.datastax.oss.driver.api.core.ConsistencyLevel
import com.datastax.oss.driver.api.core.retry.RetryPolicy
import com.datastax.oss.driver.api.core.uuid.Uuids
import com.datastax.oss.protocol.internal.util.Bytes
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.concurrent._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import scala.compat.java8.FutureConverters._

import akka.cassandra.session.scaladsl.CassandraSessionRegistry

/**
 * Journal implementation of the cassandra plugin.
 * Inheritance is possible but without any guarantees for future source compatibility.
 */
@DoNotInherit
class CassandraJournal(cfg: Config, cfgPath: String)
    extends AsyncWriteJournal
    with CassandraRecovery
    with CassandraStatements
    with NoSerializationVerificationNeeded {

  // shared config is one level above the journal specific
  private val sharedConfigPath = cfgPath.replaceAll("""\.journal$""", "")
  private val sharedConfig = context.system.settings.config.getConfig(sharedConfigPath)
  override val settings = PluginSettings(context.system, sharedConfig)

  val serialization = SerializationExtension(context.system)
  val log: LoggingAdapter = Logging(context.system, getClass)

  // For TagWriters/TagWriter children
  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case e: Exception =>
      log.error(e, "Cassandra Journal has experienced an unexpected error and requires an ActorSystem restart.")
      if (settings.journalSettings.coordinatedShutdownOnError) {
        CoordinatedShutdown(context.system).run(CassandraJournalUnexpectedError)
      }
      context.stop(context.self)
      Stop
  }

  import CassandraJournal._
  import settings._

  implicit override val ec: ExecutionContext = context.dispatcher

  // readHighestSequence must be performed after pending write for a persistenceId
  // when the persistent actor is restarted.
  // It seems like C* doesn't support session consistency so we handle it ourselves.
  // https://aphyr.com/posts/299-the-trouble-with-timestamps
  private val writeInProgress: JMap[String, Future[Done]] = new JHMap

  // Can't think of a reason why we can't have writes and deletes
  // run concurrently. This should be a very infrequently used
  // so fine to use an immutable list as the value
  private val pendingDeletes: JMap[String, List[PendingDelete]] = new JHMap

  val session: CassandraSession = CassandraSessionRegistry(context.system)
    .sessionFor(sharedConfigPath, context.dispatcher, ses => executeAllCreateKeyspaceAndTables(ses))

  private val tagWriterSession = TagWritersSession(
    session,
    journalSettings.writeProfile,
    journalSettings.readProfile,
    () => preparedWriteToTagViewWithoutMeta,
    () => preparedWriteToTagViewWithMeta,
    () => preparedWriteToTagProgress,
    () => preparedWriteTagScanning)

  protected val tagWrites: Option[ActorRef] =
    if (eventsByTagSettings.eventsByTagEnabled)
      Some(
        context.actorOf(
          TagWriters
            .props(eventsByTagSettings.tagWriterSettings, tagWriterSession)
            .withDispatcher(context.props.dispatcher),
          "tagWrites"))
    else None

  def preparedWriteMessage =
    session.prepare(writeMessage(withMeta = false))
  def preparedSelectDeletedTo: Option[Future[PreparedStatement]] = {
    if (journalSettings.supportDeletes)
      Some(session.prepare(selectDeletedTo))
    else
      None
  }
  def preparedSelectHighestSequenceNr: Future[PreparedStatement] =
    session.prepare(selectHighestSequenceNr)

  private def deletesNotSupportedException: Future[PreparedStatement] =
    Future.failed(new IllegalArgumentException(s"Deletes not supported because config support-deletes=off"))

  def preparedInsertDeletedTo: Future[PreparedStatement] = {
    if (journalSettings.supportDeletes)
      session.prepare(insertDeletedTo)
    else
      deletesNotSupportedException
  }
  def preparedDeleteMessages: Future[PreparedStatement] = {
    if (journalSettings.supportDeletes)
      session.prepare(deleteMessages)
    else
      deletesNotSupportedException
  }

  def preparedWriteMessageWithMeta =
    session.prepare(writeMessage(withMeta = true))
  def preparedSelectMessages =
    session.prepare(selectMessages)

  implicit val materializer: ActorMaterializer =
    ActorMaterializer()(context.system)

  private[akka] lazy val queries =
    PersistenceQuery(context.system.asInstanceOf[ExtendedActorSystem])
      .readJournalFor[CassandraReadJournal](s"$sharedConfigPath.query")

  override def preStart(): Unit = {
    // eager initialization, but not from constructor
    self ! CassandraJournal.Init
  }

  override def receivePluginInternal: Receive = {
    case WriteFinished(persistenceId, f) =>
      writeInProgress.remove(persistenceId, f)

    case DeleteFinished(persistenceId, toSequenceNr, result) =>
      log.debug("Delete finished for persistence id [{}] to [{}] result [{}]", persistenceId, toSequenceNr, result)
      pendingDeletes.get(persistenceId) match {
        case null =>
          log.error(
            "Delete finished but not in pending. Please raise a bug with logs. PersistenceId: [{}]",
            persistenceId)
        case Nil =>
          log.error(
            "Delete finished but not in pending (empty). Please raise a bug with logs. PersistenceId: [{}]",
            persistenceId)
        case current :: tail =>
          current.p.complete(result)
          tail match {
            case Nil =>
              pendingDeletes.remove(persistenceId)
            case next :: _ =>
              pendingDeletes.put(persistenceId, tail)
              delete(next.pid, next.toSequenceNr)
          }
      }

    case CassandraJournal.Init =>
      // try initialize early, to be prepared for first real request
      preparedWriteMessage
      preparedWriteMessageWithMeta
      preparedSelectMessages
      preparedSelectHighestSequenceNr
      if (journalSettings.supportDeletes) {
        preparedDeleteMessages
        preparedSelectDeletedTo
        preparedInsertDeletedTo
      }
      queries.initialize()

      if (eventsByTagSettings.eventsByTagEnabled) {
        preparedSelectTagProgress
        preparedSelectTagProgressForPersistenceId
        preparedWriteToTagProgress
        preparedWriteToTagViewWithoutMeta
        preparedWriteToTagViewWithMeta
        preparedWriteTagScanning
      }
  }

  override def postStop(): Unit = {
    session.close()
  }

  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = {
    // we need to preserve the order / size of this sequence even though we don't map
    // AtomicWrites 1:1 with a C* insert
    //
    // We must NOT catch serialization exceptions here because rejections will cause
    // holes in the sequence number series and we use the sequence numbers to detect
    // missing (delayed) events in the eventByTag query.
    //
    // Note that we assume that all messages have the same persistenceId, which is
    // the case for Akka 2.4.2.
    def serialize(aw: Seq[(PersistentRepr, UUID)]): Future[SerializedAtomicWrite] = {
      val serializedEventsFut: Future[Seq[Serialized]] =
        Future.sequence(aw.map {
          case (pr, uuid) =>
            val (pr2, tags) = pr.payload match {
              case Tagged(payload, ts) =>
                (pr.withPayload(payload), ts)
              case _ =>
                (pr, Set.empty[String])
            }
            serializeEvent(pr2, tags, uuid, eventsByTagSettings.bucketSize, serialization, context.system)
        })

      serializedEventsFut.map { serializedEvents =>
        SerializedAtomicWrite(aw.head._1.persistenceId, serializedEvents)
      }
    }

    val writesWithUuids: Seq[Seq[(PersistentRepr, UUID)]] =
      messages.map(aw => aw.payload.map(pr => (pr, generateUUID(pr))))

    val writeInProgressForPersistentId = Promise[Done]
    val pid = messages.head.persistenceId
    writeInProgress.put(pid, writeInProgressForPersistentId.future)

    val toReturn: Future[Nil.type] = Future.sequence(writesWithUuids.map(w => serialize(w))).flatMap {
      serialized: Seq[SerializedAtomicWrite] =>
        val result: Future[Any] =
          if (messages.map(_.payload.size).sum <= journalSettings.maxMessageBatchSize) {
            // optimize for the common case
            writeMessages(serialized)
          } else {

            //if presistAll was used, single AtomicWrite can already contain complete batch, so we need to regroup writes correctly
            val groups: List[List[SerializedAtomicWrite]] = groupedWrites(serialized.toList.reverse, Nil, Nil)

            // execute the groups in sequence
            def rec(todo: List[List[SerializedAtomicWrite]]): Future[Any] =
              todo match {
                case write :: remainder =>
                  writeMessages(write).flatMap(_ => rec(remainder))
                case Nil => Future.successful(())
              }

            rec(groups)
          }
        result.map { _ =>
          tagWrites.foreach(_ ! extractTagWrites(serialized))
          Nil
        }

    }

    // if the write fails still need to remove state from the map
    toReturn.onComplete { _ =>
      sendWriteFinished(pid, writeInProgressForPersistentId)
    }

    toReturn
  }

  //Regroup batches by payload size
  @tailrec
  private def groupedWrites(
      reversed: List[SerializedAtomicWrite],
      currentGroup: List[SerializedAtomicWrite],
      grouped: List[List[SerializedAtomicWrite]]): List[List[SerializedAtomicWrite]] = reversed match {
    case Nil => currentGroup +: grouped
    case x :: xs if currentGroup.size + x.payload.size < journalSettings.maxMessageBatchSize =>
      groupedWrites(xs, x +: currentGroup, grouped)
    case x :: xs => groupedWrites(xs, List(x), currentGroup +: grouped)
  }

  def sendWriteFinished(pid: String, writeInProgressForPid: Promise[Done]): Unit = {
    self ! WriteFinished(pid, writeInProgressForPid.future)
    writeInProgressForPid.success(Done)
  }

  /**
   * UUID generation is deliberately externalized to allow subclasses to customize the time based uuid for special cases.
   * see https://discuss.lightbend.com/t/akka-persistence-cassandra-events-by-tags-bucket-size-based-on-time-vs-burst-load/1411 and make sure you understand the risk of doing this wrong.
   */
  protected def generateUUID(pr: PersistentRepr): UUID = Uuids.timeBased()

  private def extractTagWrites(serialized: Seq[SerializedAtomicWrite]): BulkTagWrite = {
    if (serialized.isEmpty) BulkTagWrite(Nil, Nil)
    else if (serialized.size == 1 && serialized.head.payload.size == 1) {
      // optimization for one single event, which is the typical case
      val s = serialized.head.payload.head
      if (s.tags.isEmpty) BulkTagWrite(Nil, s :: Nil)
      else BulkTagWrite(s.tags.map(tag => TagWrite(tag, s :: Nil)).toList, Nil)
    } else {
      val messagesByTag: Map[String, Seq[Serialized]] =
        serialized.flatMap(_.payload).flatMap(s => s.tags.map((_, s))).groupBy(_._1).map {
          case (tag, messages) => (tag, messages.map(_._2))
        }
      val messagesWithoutTag =
        for {
          a <- serialized
          b <- a.payload
          if b.tags.isEmpty
        } yield b

      val writesWithTags: immutable.Seq[TagWrite] = messagesByTag.map {
        case (tag, writes) => TagWrite(tag, writes)
      }.toList

      BulkTagWrite(writesWithTags, messagesWithoutTag)
    }

  }

  private def writeMessages(atomicWrites: Seq[SerializedAtomicWrite]): Future[Unit] = {
    val boundStatements: Seq[Future[BoundStatement]] = statementGroup(atomicWrites)
    boundStatements.size match {
      case 1 =>
        boundStatements.head.flatMap(execute(_))
      case 0 => Future.successful(())
      case _ =>
        Future.sequence(boundStatements).flatMap { stmts =>
          executeBatch(batch => stmts.foldLeft(batch) { case (acc, next) => acc.add(next) })
        }
    }
  }

  private def statementGroup(atomicWrites: Seq[SerializedAtomicWrite]): Seq[Future[BoundStatement]] = {
    val maxPnr = partitionNr(atomicWrites.last.payload.last.sequenceNr, journalSettings.targetPartitionSize)
    val firstSeq = atomicWrites.head.payload.head.sequenceNr
    val minPnr = partitionNr(firstSeq, journalSettings.targetPartitionSize)
    val persistenceId: String = atomicWrites.head.persistenceId
    val all = atomicWrites.flatMap(_.payload)

    // reading assumes sequence numbers are in the right partition or partition + 1
    // even if we did allow this it would perform terribly as large C* batches are not good
    require(
      maxPnr - minPnr <= 1,
      "Do not support AtomicWrites that span 3 partitions. Keep AtomicWrites <= max partition size.")

    val writes: Seq[Future[BoundStatement]] = all.map { m: Serialized =>
      // using two separate statements with or without the meta data columns because
      // then users doesn't have to alter table and add the new columns if they don't use
      // the meta data feature
      val stmt =
        if (m.meta.isDefined) preparedWriteMessageWithMeta
        else preparedWriteMessage

      stmt.map { stmt =>
        val bs = stmt
          .bind()
          .setString("persistence_id", persistenceId)
          .setLong("partition_nr", maxPnr)
          .setLong("sequence_nr", m.sequenceNr)
          .setUuid("timestamp", m.timeUuid)
          // Keeping as text for backward compatibility
          .setString("timebucket", m.timeBucket.key.toString)
          .setString("writer_uuid", m.writerUuid)
          .setInt("ser_id", m.serId)
          .setString("ser_manifest", m.serManifest)
          .setString("event_manifest", m.eventAdapterManifest)
          .setByteBuffer("event", m.serialized)
          .setSet("tags", m.tags.asJava, classOf[String])

        // meta data, if any
        m.meta
          .map(meta => {
            bs.setInt("meta_ser_id", meta.serId)
              .setString("meta_ser_manifest", meta.serManifest)
              .setByteBuffer("meta", meta.serialized)
          })
          .getOrElse(bs)
      }
    }

    writes
  }

  /**
   * It is assumed that this is only called during a replay and if fromSequenceNr == highest
   * then asyncReplayMessages won't be called. In that case the tag progress is updated
   * in here rather than during replay messages.
   */
  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    log.debug("[{}] asyncReadHighestSequenceNr [{}] [{}]", persistenceId, fromSequenceNr, sender())
    val highestSequenceNr = writeInProgress.get(persistenceId) match {
      case null =>
        asyncReadHighestSequenceNrInternal(persistenceId, fromSequenceNr)
      case f =>
        f.flatMap(_ => asyncReadHighestSequenceNrInternal(persistenceId, fromSequenceNr))
    }

    val toReturn = if (eventsByTagSettings.eventsByTagEnabled) {

      // This relies on asyncReadHighestSequenceNr having the correct sender()
      // No other calls into the async journal have this as they are called from Future callbacks
      val persistentActor = sender()

      for {
        seqNr <- highestSequenceNr
        _ <- sendPersistentActorStarting(persistenceId, persistentActor, tagWrites.get)
        _ <- if (seqNr == fromSequenceNr && seqNr != 0) {
          log.debug("[{}] snapshot is current so replay won't be required. Calculating tag progress now", persistenceId)
          val scanningSeqNrFut = tagScanningStartingSequenceNr(persistenceId)
          for {
            tp <- lookupTagProgress(persistenceId)
            _ <- setTagProgress(persistenceId, tp, tagWrites.get)
            scanningSeqNr <- scanningSeqNrFut
            _ <- sendPreSnapshotTagWrites(scanningSeqNr, fromSequenceNr, persistenceId, Long.MaxValue, tp)
          } yield seqNr
        } else if (seqNr == 0) {
          log.debug("[{}] New pid. Sending blank tag progress. [{}]", persistenceId, persistentActor)
          setTagProgress(persistenceId, Map.empty, tagWrites.get)
        } else {
          Future.successful(())
        }
      } yield seqNr
    } else {
      highestSequenceNr
    }

    toReturn.onComplete { highestSeq =>
      log.debug("asyncReadHighestSequenceNr {} returning {}", persistenceId, highestSeq)
    }

    toReturn
  }

  /**
   * Not thread safe. Assumed to only be called from the journal actor.
   * However, unlike asyncWriteMessages it can be called before the previous Future completes
   */
  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {

    // TODO could "optimize" away deletes that overlap?
    pendingDeletes.get(persistenceId) match {
      case null =>
        log.debug("[{}] No outstanding delete. Sequence nr [{}]", persistenceId, toSequenceNr)
        // fast path, no outstanding deletes for this persistenceId
        val p = Promise[Unit]()
        pendingDeletes.put(persistenceId, List(PendingDelete(persistenceId, toSequenceNr, p)))
        delete(persistenceId, toSequenceNr)
        p.future
      case otherDeletes =>
        if (otherDeletes.length > journalSettings.maxConcurrentDeletes) {
          log.error(
            "[}}] Over [{}] outstanding deletes. Failing delete",
            persistenceId,
            journalSettings.maxConcurrentDeletes)
          Future.failed(
            new RuntimeException(
              s"Over ${journalSettings.maxConcurrentDeletes} outstanding deletes for persistenceId $persistenceId"))
        } else {
          log.debug(
            "[{}] outstanding delete. Delete to seqNr [{}] will be scheduled after previous one finished.",
            persistenceId,
            toSequenceNr)
          val p = Promise[Unit]()
          pendingDeletes.put(persistenceId, otherDeletes :+ PendingDelete(persistenceId, toSequenceNr, p))
          p.future
        }
    }
  }

  private def delete(persistenceId: String, toSequenceNr: Long): Future[Unit] = {

    def physicalDelete(lowestPartition: Long, highestPartition: Long, toSeqNr: Long): Future[Done] = {
      if (settings.cassandra2xCompat) {
        def asyncDeleteMessages(partitionNr: Long, messageIds: Seq[MessageId]): Future[Unit] = {
          val boundStatements = messageIds.map(mid =>
            preparedDeleteMessages.map(_.bind(mid.persistenceId, partitionNr: JLong, mid.sequenceNr: JLong)))
          Future.sequence(boundStatements).flatMap { stmts =>
            executeBatch(batch => stmts.foldLeft(batch) { case (acc, next) => acc.add(next) })
          }
        }

        val partitionInfos = (lowestPartition to highestPartition).map(partitionInfo(persistenceId, _, toSeqNr))
        val deleteResult =
          Future.sequence(partitionInfos.map(future =>
            future.flatMap(pi => {
              Future.sequence((pi.minSequenceNr to pi.maxSequenceNr).grouped(journalSettings.maxMessageBatchSize).map {
                group =>
                  {
                    val groupDeleteResult =
                      asyncDeleteMessages(pi.partitionNr, group.map(MessageId(persistenceId, _)))
                    groupDeleteResult.failed.foreach { e =>
                      log.warning(
                        s"Unable to complete deletes for persistence id {}, toSequenceNr {}. " +
                        "The plugin will continue to function correctly but you will need to manually delete the old messages. " +
                        "Caused by: [{}: {}]",
                        persistenceId,
                        toSequenceNr,
                        e.getClass.getName,
                        e.getMessage)
                    }
                    groupDeleteResult
                  }
              })
            })))
        deleteResult.map(_ => Done)

      } else {
        val deleteResult =
          Future.sequence((lowestPartition to highestPartition).map { partitionNr =>
            val boundDeleteMessages =
              preparedDeleteMessages.map(_.bind(persistenceId, partitionNr: JLong, toSeqNr: JLong))
            boundDeleteMessages.flatMap(execute(_))
          })
        deleteResult.failed.foreach { e =>
          log.warning(
            "Unable to complete deletes for persistence id {}, toSequenceNr {}. " +
            "The plugin will continue to function correctly but you will need to manually delete the old messages. " +
            "Caused by: [{}: {}]",
            persistenceId,
            toSequenceNr,
            e.getClass.getName,
            e.getMessage)
        }
        deleteResult.map(_ => Done)
      }
    }

    /**
     * Deletes the events by inserting into the metadata table deleted_to
     * and physically deletes the rows.
     */
    def logicalAndPhysicalDelete(highestDeletedSequenceNumber: Long, highestSequenceNr: Long): Future[Done] = {
      val lowestPartition = partitionNr(highestDeletedSequenceNumber + 1, journalSettings.targetPartitionSize)
      val toSeqNr = math.min(toSequenceNr, highestSequenceNr)
      val highestPartition = partitionNr(toSeqNr, journalSettings.targetPartitionSize) + 1 // may have been moved to the next partition
      val logicalDelete =
        if (toSeqNr <= highestDeletedSequenceNumber) {
          // already deleted same or higher sequence number, don't update highestDeletedSequenceNumber,
          // but perform the physical delete (again), may be a retry request
          Future.successful(())
        } else {
          val boundInsertDeletedTo =
            preparedInsertDeletedTo.map(_.bind(persistenceId, toSeqNr: JLong))
          boundInsertDeletedTo.flatMap(execute)
        }
      logicalDelete.flatMap(_ => physicalDelete(lowestPartition, highestPartition, toSeqNr))
    }

    val deleteResult = for {
      highestDeletedSequenceNumber <- asyncHighestDeletedSequenceNumber(persistenceId)
      highestSequenceNr <- {
        // MaxValue may be used as magic value to delete all events without specifying actual toSequenceNr
        if (toSequenceNr == Long.MaxValue)
          asyncFindHighestSequenceNr(persistenceId, highestDeletedSequenceNumber, journalSettings.targetPartitionSize)
        else
          Future.successful(toSequenceNr)
      }
      _ <- logicalAndPhysicalDelete(highestDeletedSequenceNumber, highestSequenceNr)
    } yield ()

    // Kick off any pending deletes when finished.
    deleteResult.onComplete { result =>
      self ! DeleteFinished(persistenceId, toSequenceNr, result)
    }

    deleteResult
  }

  private def partitionInfo(persistenceId: String, partitionNr: Long, maxSequenceNr: Long): Future[PartitionInfo] = {
    val boundSelectHighestSequenceNr = preparedSelectHighestSequenceNr.map(_.bind(persistenceId, partitionNr: JLong))
    boundSelectHighestSequenceNr
      .flatMap(selectOne)
      .map(
        row =>
          row
            .map(s =>
              PartitionInfo(partitionNr, minSequenceNr(partitionNr), math.min(s.getLong("sequence_nr"), maxSequenceNr)))
            .getOrElse(PartitionInfo(partitionNr, minSequenceNr(partitionNr), -1)))
  }

  private[akka] def asyncHighestDeletedSequenceNumber(persistenceId: String): Future[Long] = {
    preparedSelectDeletedTo match {
      case Some(pstmt) =>
        val boundSelectDeletedTo = pstmt.map(_.bind(persistenceId))
        boundSelectDeletedTo.flatMap(selectOne).map(rowOption => rowOption.map(_.getLong("deleted_to")).getOrElse(0))
      case None =>
        Future.successful(0L)
    }
  }

  private[akka] def asyncReadLowestSequenceNr(
      persistenceId: String,
      fromSequenceNr: Long,
      highestDeletedSequenceNumber: Long,
      readConsistency: Option[ConsistencyLevel],
      retryPolicy: Option[RetryPolicy]): Future[Long] = {
    queries
      .eventsByPersistenceId(
        persistenceId,
        fromSequenceNr,
        highestDeletedSequenceNumber,
        1,
        None,
        settings.journalSettings.readProfile,
        "asyncReadLowestSequenceNr",
        extractor = Extractors.sequenceNumber(eventDeserializer, serialization))
      .map(_.sequenceNr)
      .runWith(Sink.headOption)
      .map {
        case Some(sequenceNr) => sequenceNr
        case None             => fromSequenceNr
      }
  }

  private[akka] def asyncFindHighestSequenceNr(
      persistenceId: String,
      fromSequenceNr: Long,
      partitionSize: Long): Future[Long] = {
    def find(currentPnr: Long, currentSnr: Long, foundEmptyPartition: Boolean): Future[Long] = {
      // if every message has been deleted and thus no sequence_nr the driver gives us back 0 for "null" :(
      val boundSelectHighestSequenceNr = preparedSelectHighestSequenceNr.map(ps => {
        val bound = ps.bind(persistenceId, currentPnr: JLong)
        bound

      })
      boundSelectHighestSequenceNr
        .flatMap(selectOne)
        .map { rowOption =>
          rowOption.map(_.getLong("sequence_nr"))
        }
        .flatMap {
          case None | Some(0) =>
            // never been to this partition, query one more partition because AtomicWrite can span (skip)
            // one entire partition
            // Some(0) when old schema with static used column, everything deleted in this partition
            if (foundEmptyPartition) Future.successful(currentSnr)
            else find(currentPnr + 1, currentSnr, foundEmptyPartition = true)
          case Some(nextHighest) =>
            find(currentPnr + 1, nextHighest, foundEmptyPartition = false)
        }
    }

    find(partitionNr(fromSequenceNr, partitionSize), fromSequenceNr, foundEmptyPartition = false)
  }

  private def executeBatch(body: BatchStatement => BatchStatement): Future[Unit] = {
    var batch =
      new BatchStatementBuilder(BatchType.UNLOGGED).build().setExecutionProfileName(journalSettings.writeProfile)
    batch = body(batch)
    session.underlying().flatMap(_.executeAsync(batch).toScala).map(_ => ())
  }

  def selectOne[T <: Statement[T]](stmt: Statement[T]): Future[Option[Row]] = {
    session.selectOne(stmt.setExecutionProfileName(journalSettings.readProfile))
  }

  private def minSequenceNr(partitionNr: Long): Long =
    partitionNr * journalSettings.targetPartitionSize + 1

  private def execute[T <: Statement[T]](stmt: Statement[T]): Future[Unit] = {
    session.executeWrite(stmt.setExecutionProfileName(journalSettings.writeProfile)).map(_ => ())
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object CassandraJournal {
  private[akka] type Tag = String
  private[akka] type PersistenceId = String
  private[akka] type SequenceNr = Long
  private[akka] type TagPidSequenceNr = Long

  private case object Init

  private case class WriteFinished(pid: String, f: Future[Done]) extends NoSerializationVerificationNeeded

  private case class DeleteFinished(pid: String, toSequenceNr: Long, f: Try[Unit])
      extends NoSerializationVerificationNeeded
  private case class PendingDelete(pid: String, toSequenceNr: Long, p: Promise[Unit])
      extends NoSerializationVerificationNeeded

  private case class SerializedAtomicWrite(persistenceId: String, payload: Seq[Serialized])

  private[akka] case class Serialized(
      persistenceId: String,
      sequenceNr: Long,
      serialized: ByteBuffer,
      tags: Set[String],
      eventAdapterManifest: String,
      serManifest: String,
      serId: Int,
      writerUuid: String,
      meta: Option[SerializedMeta],
      timeUuid: UUID,
      timeBucket: TimeBucket)

  private[akka] case class SerializedMeta(serialized: ByteBuffer, serManifest: String, serId: Int)

  private case class PartitionInfo(partitionNr: Long, minSequenceNr: Long, maxSequenceNr: Long)
  private case class MessageId(persistenceId: String, sequenceNr: Long)

  class EventDeserializer(system: ActorSystem) {

    private val serialization = SerializationExtension(system)

    // caching to avoid repeated check via ColumnDefinitions
    private def hasColumn(column: String, row: Row, cached: Option[Boolean], updateCache: Boolean => Unit): Boolean = {
      cached match {
        case Some(b) => b
        case None =>
          val b = row.getColumnDefinitions.contains(column)
          updateCache(b)
          b
      }
    }

    @volatile private var _hasMetaColumns: Option[Boolean] = None
    private val updateMetaColumnsCache: Boolean => Unit = b => _hasMetaColumns = Some(b)
    def hasMetaColumns(row: Row): Boolean =
      hasColumn("meta", row, _hasMetaColumns, updateMetaColumnsCache)

    @volatile private var _hasOldTagsColumns: Option[Boolean] = None
    private val updateOldTagsColumnsCache: Boolean => Unit = b => _hasOldTagsColumns = Some(b)
    def hasOldTagsColumns(row: Row): Boolean =
      hasColumn("tag1", row, _hasOldTagsColumns, updateOldTagsColumnsCache)

    @volatile private var _hasTagsColumn: Option[Boolean] = None
    private val updateTagsColumnCache: Boolean => Unit = b => _hasTagsColumn = Some(b)
    def hasTagsColumn(row: Row): Boolean =
      hasColumn("tags", row, _hasTagsColumn, updateTagsColumnCache)

    def deserializeEvent(row: Row, async: Boolean)(implicit ec: ExecutionContext): Future[Any] =
      try {

        def meta: OptionVal[AnyRef] = {
          if (hasMetaColumns(row)) {
            row.getByteBuffer("meta") match {
              case null =>
                OptionVal.None // no meta data
              case metaBytes =>
                // has meta data, wrap in EventWithMetaData
                val metaSerId = row.getInt("meta_ser_id")
                val metaSerManifest = row.getString("meta_ser_manifest")
                val meta = serialization.deserialize(Bytes.getArray(metaBytes), metaSerId, metaSerManifest) match {
                  case Success(m) => m
                  case Failure(_) =>
                    // don't fail replay/query because of deserialization problem with meta data
                    // see motivation in UnknownMetaData
                    UnknownMetaData(metaSerId, metaSerManifest)
                }
                OptionVal.Some(meta)
            }
          } else {
            // for backwards compatibility, when table was not altered, meta columns not added
            OptionVal.None // no meta data
          }
        }

        val bytes = Bytes.getArray(row.getByteBuffer("event"))
        val serId = row.getInt("ser_id")
        val manifest = row.getString("ser_manifest")

        serialization.serializerByIdentity.get(serId) match {
          case Some(asyncSerializer: AsyncSerializer) =>
            Serialization.withTransportInformation(system.asInstanceOf[ExtendedActorSystem]) { () =>
              asyncSerializer.fromBinaryAsync(bytes, manifest).map { event =>
                meta match {
                  case OptionVal.None    => event
                  case OptionVal.Some(m) => EventWithMetaData(event, m)
                }
              }
            }

          case _ =>
            def deserializedEvent: AnyRef = {
              // Serialization.deserialize adds transport info
              val event = serialization.deserialize(bytes, serId, manifest).get
              meta match {
                case OptionVal.None    => event
                case OptionVal.Some(m) => EventWithMetaData(event, m)
              }
            }

            if (async) Future(deserializedEvent)
            else Future.successful(deserializedEvent)
        }

      } catch {
        case NonFatal(e) => Future.failed(e)
      }
  }
}
