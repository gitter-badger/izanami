package domains.abtesting

import akka.actor.ActorSystem
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Flow
import akka.{Done, NotUsed}
import domains.abtesting.Experiment.ExperimentKey
import domains.events.EventStore
import domains.{AuthInfo, Key}
import play.api.libs.json.{JsObject, Json}
import store._

import scala.concurrent.{ExecutionContext, Future}

case class Variant(id: String,
                   name: String,
                   description: String,
                   traffic: Double,
                   currentPopulation: Option[Int] = Some(0)) {
  def incrementPopulation: Variant =
    copy(currentPopulation = currentPopulation.map(_ + 1).orElse(Some(1)))
}

object Variant {
  implicit val format = Json.format[Variant]
}

case class Experiment(id: ExperimentKey,
                      name: String,
                      description: String,
                      enabled: Boolean,
                      variants: Seq[Variant]) {
  def isAllowed = Key.isAllowed(id) _

  def addOrReplaceVariant(variant: Variant): Experiment =
    copy(variants = variants.map {
      case v if v.id == variant.id => variant
      case v                       => v
    })
}

object Experiment {

  type ExperimentKey = Key

  implicit val format = Json.format[Experiment]

  def toGraph(clientId: String)(implicit ec: ExecutionContext,
                                experimentStore: ExperimentStore,
                                variantBindingStore: VariantBindingStore)
    : Flow[Experiment, JsObject, NotUsed] = {
    import VariantBinding._
    Flow[Experiment]
      .mapAsyncUnordered(2) { experiment =>
        variantBindingStore
          .getById(VariantBindingKey(experiment.id, clientId))
          .one
          .flatMap {
            case Some(v) =>
              FastFuture.successful(
                (experiment.id.jsPath \ "variant")
                  .write[String]
                  .writes(v.variantId))
            case None =>
              VariantBinding
                .createVariantForClient(experiment.id, clientId)
                .map {
                  case Right(v) =>
                    (experiment.id.jsPath \ "variant")
                      .write[String]
                      .writes(v.id)
                  case Left(e) =>
                    Json.obj()
                }
          }
      }
      .fold(Json.obj()) { (acc, js) =>
        acc.deepMerge(js.as[JsObject])
      }
  }

  def isAllowed(key: Key)(auth: Option[AuthInfo]) = Key.isAllowed(key)(auth)

}

trait ExperimentStore extends DataStore[ExperimentKey, Experiment]

object ExperimentStore {
  def apply(jsonStore: JsonDataStore,
            eventStore: EventStore,
            system: ActorSystem) =
    new ExperimentStoreImpl(jsonStore, eventStore, system)
}

class ExperimentStoreImpl(jsonStore: JsonDataStore,
                          eventStore: EventStore,
                          system: ActorSystem)
    extends ExperimentStore {

  import Experiment._
  import domains.events.Events.{
    ExperimentCreated,
    ExperimentDeleted,
    ExperimentUpdated
  }
  import store.Result._
  import system.dispatcher

  implicit val s = system
  implicit val es = eventStore

  override def create(id: ExperimentKey,
                      data: Experiment): Future[Result[Experiment]] =
    jsonStore.create(id, format.writes(data)).to[Experiment].andPublishEvent {
      r =>
        ExperimentCreated(id, r)
    }

  override def update(oldId: ExperimentKey,
                      id: ExperimentKey,
                      data: Experiment): Future[Result[Experiment]] =
    jsonStore
      .update(oldId, id, format.writes(data))
      .to[Experiment]
      .andPublishEvent { r =>
        ExperimentUpdated(id, data, r)
      }

  override def delete(id: ExperimentKey): Future[Result[Experiment]] =
    jsonStore.delete(id).to[Experiment].andPublishEvent { r =>
      ExperimentDeleted(id, r)
    }

  override def deleteAll(patterns: Seq[String]): Future[Result[Done]] =
    jsonStore.deleteAll(patterns)

  override def getById(id: ExperimentKey): FindResult[Experiment] =
    JsonFindResult[Experiment](jsonStore.getById(id))

  override def getByIdLike(
      patterns: Seq[String],
      page: Int,
      nbElementPerPage: Int): Future[PagingResult[Experiment]] =
    jsonStore
      .getByIdLike(patterns, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def getByIdLike(patterns: Seq[String]): FindResult[Experiment] =
    JsonFindResult[Experiment](jsonStore.getByIdLike(patterns))

  override def count(patterns: Seq[String]): Future[Long] =
    jsonStore.count(patterns)
}

case class VariantResult(variant: Option[Variant] = None,
                         displayed: Long = 0,
                         won: Long = 0,
                         transformation: Double = 0,
                         events: Seq[ExperimentVariantEvent] = Seq.empty)

object VariantResult {
  implicit val format = Json.format[VariantResult]

  def transformation(displayed: Long, won: Long): Double = displayed match {
    case 0 => 0.0
    case _ => (won * 100) / displayed
  }
}

case class ExperimentResult(experiment: Experiment,
                            results: Seq[VariantResult] =
                              Seq.empty[VariantResult])

object ExperimentResult {
  implicit val format = Json.format[ExperimentResult]
}
