package izanami.scaladsl

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.stream._
import izanami.FeatureEvent.{FeatureCreated, FeatureUpdated}
import izanami.IzanamiBackend.SseBackend
import izanami.Strategy.DevStrategy
import izanami._
import izanami.commons.{HttpClient, IzanamiException}
import izanami.configs.{
  FallbackConfigStategy,
  FetchConfigClient,
  FetchWithCacheConfigClient,
  SmartCacheConfigClient
}
import izanami.experiments.{
  FallbackExperimentStrategy,
  FetchExperimentsStrategy
}
import izanami.features.{
  FallbackFeatureStategy,
  FetchFeatureClient,
  FetchWithCacheFeatureClient,
  SmartCacheFeatureClient
}
import izanami.scaladsl.ConfigEvent.{ConfigCreated, ConfigUpdated}
import org.reactivestreams.Publisher
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.Try

object IzanamiClient {

  def apply(config: ClientConfig)(
      implicit actorSystem: ActorSystem): IzanamiClient =
    new IzanamiClient(config)

}

class IzanamiClient(val config: ClientConfig)(
    implicit val actorSystem: ActorSystem) {
  import akka.event.Logging

  private val client: HttpClient = HttpClient(actorSystem, config)

  private implicit val materializer: Materializer =
    ActorMaterializer(
      ActorMaterializerSettings(actorSystem).withDispatcher(config.dispatcher))(
      actorSystem)

  implicit val izanamiDispatcher =
    IzanamiDispatcher(config.dispatcher, actorSystem)

  private val eventSource: Source[IzanamiEvent, NotUsed] = Source
    .lazily(
      () =>
        client
          .eventStream()
          .runWith(BroadcastHub.sink(1024)))
    .mapMaterializedValue(_ => NotUsed)

  def configClient(
      strategy: Strategy,
      fallback: Configs = Configs(Seq.empty)
  ) = {
    val source = config.backend match {
      case SseBackend => eventSource
      case _ =>
        Source.failed(
          new IllegalStateException(
            "Notifications are disabled for this strategy"))
    }
    strategy match {
      case DevStrategy =>
        FallbackConfigStategy(fallback)
      case Strategy.FetchStrategy =>
        FetchConfigClient(client, config, fallback, source)
      case s: Strategy.FetchWithCacheStrategy =>
        FetchWithCacheConfigClient(
          config,
          fallback,
          FetchConfigClient(client, config, fallback, source),
          s)
      case s: Strategy.CacheWithSseStrategy =>
        SmartCacheConfigClient(
          config,
          FetchConfigClient(client, config, fallback, eventSource),
          fallback,
          s)
      case s: Strategy.CacheWithPollingStrategy =>
        SmartCacheConfigClient(
          config,
          FetchConfigClient(client, config, fallback, source),
          fallback,
          s)
    }
  }

  def featureClient(
      strategy: Strategy,
      fallback: ClientConfig => Features = clientConfig =>
        Features(clientConfig, Seq.empty, Seq.empty)
  ) = {
    val source = config.backend match {
      case SseBackend => eventSource
      case _ =>
        Source.failed(
          new IllegalStateException(
            "Notifications are disabled for this strategy"))
    }
    val fb = fallback(config)
    strategy match {
      case DevStrategy =>
        FallbackFeatureStategy(fb)
      case Strategy.FetchStrategy =>
        FetchFeatureClient(client, config, fb, source)
      case s: Strategy.FetchWithCacheStrategy =>
        FetchWithCacheFeatureClient(
          config,
          FetchFeatureClient(client, config, fb, source),
          s)
      case s: Strategy.CacheWithSseStrategy =>
        SmartCacheFeatureClient(
          config,
          FetchFeatureClient(client, config, fallback(config), eventSource),
          fb,
          s)
      case s: Strategy.CacheWithPollingStrategy =>
        SmartCacheFeatureClient(config,
                                FetchFeatureClient(client, config, fb, source),
                                fb,
                                s)
    }
  }

  def experimentClient(strategy: Strategy,
                       fallback: Experiments = Experiments()) =
    strategy match {
      case DevStrategy =>
        FallbackExperimentStrategy(fallback)
      case Strategy.FetchStrategy =>
        FetchExperimentsStrategy(client, fallback)
      case s =>
        throw new IllegalArgumentException(
          s"This strategy $s is not not supported for experiments")
    }

  def proxy(): Proxy = Proxy(None, None, None)

  def proxy(featureClient: FeatureClient,
            configClient: ConfigClient,
            experimentClient: ExperimentsClient): Proxy =
    Proxy(Some(featureClient), Some(configClient), Some(experimentClient))

}

case class Proxy(
    featureClient: Option[FeatureClient],
    configClient: Option[ConfigClient],
    experimentClient: Option[ExperimentsClient],
    featurePattern: String = "*",
    configPattern: String = "*",
    experimentPattern: String = "*"
)(implicit actorSystem: ActorSystem, izanamiDispatcher: IzanamiDispatcher) {

  import izanamiDispatcher.ec
  val logger = Logging(actorSystem, this.getClass.getSimpleName)

  def withFeatureClient(featureClient: FeatureClient) =
    this.copy(featureClient = Some(featureClient))
  def withConfigClient(configClient: ConfigClient) =
    this.copy(configClient = Some(configClient))
  def withExperimentsClient(experimentsClient: ExperimentsClient) =
    this.copy(experimentClient = Some(experimentsClient))
  def withFeaturePattern(pattern: String) = this.copy(featurePattern = pattern)
  def withConfigPattern(pattern: String) = this.copy(configPattern = pattern)
  def withExperimentPattern(pattern: String) =
    this.copy(experimentPattern = pattern)

  def statusAndJsonResponse(
      context: Option[JsObject] = None,
      userId: Option[String] = None): Future[(Int, JsValue)] = {

    require(context != null, "context should not be null")
    require(userId != null, "userId should not be null")

    val features: Future[JsObject] =
      featureClient
        .map(
          cli =>
            context
              .map(
                ctx =>
                  cli
                    .features(featurePattern, ctx)
                    .map(f => Json.obj("features" -> f.tree()))
              )
              .getOrElse(
                cli
                  .features(featurePattern)
                  .map(f => Json.obj("features" -> f.tree()))
            )
        )
        .getOrElse(FastFuture.successful(Json.obj("features" -> Json.obj())))

    val configs: Future[JsObject] = configClient
      .map(
        _.configs(configPattern)
          .map(c => Json.obj("configurations" -> c.tree()))
      )
      .getOrElse(
        FastFuture.successful(Json.obj("configurations" -> Json.obj())))

    val experiments: Future[JsObject] = userId
      .flatMap(id => experimentClient.map(_.tree(experimentPattern, id)))
      .getOrElse(FastFuture.successful(Json.obj()))
      .map(exp => Json.obj("experiments" -> exp))

    Future
      .sequence(Seq(features, configs, experiments))
      .map(
        _.foldLeft(Json.obj())(_ deepMerge _)
      )
      .map((200, _))
      .recover {
        case e =>
          logger.error(e, "Error getting izanami tree datas")
          (400, Json.obj("errors" -> "Error getting izanami tree datas"))
      }

  }

  def statusAndStringResponse(
      context: Option[JsObject] = None,
      userId: Option[String] = None): Future[(Int, String)] =
    statusAndJsonResponse(context, userId).map {
      case (status, json) => (status, Json.stringify(json))
    }

  def markVariantDisplayed(experimentId: String,
                           clientId: String): Future[(Int, JsValue)] =
    experimentClient
      .map(
        _.markVariantDisplayed(experimentId, clientId)
          .map { event =>
            (200, Json.toJson(event))
          }
          .recover {
            case e =>
              logger.error(e, "Error while marking variant displayed")
              (400,
               Json.obj("errors" -> "Error while marking variant displayed"))
          }
      )
      .getOrElse(FastFuture.successful((200, Json.obj())))

  def markVariantDisplayedStringResp(experimentId: String,
                                     clientId: String): Future[(Int, String)] =
    markVariantDisplayed(experimentId, clientId).map {
      case (status, json) => (status, Json.stringify(json))
    }

  def markVariantWon(experimentId: String,
                     clientId: String): Future[(Int, JsValue)] =
    experimentClient
      .map(
        _.markVariantWon(experimentId, clientId)
          .map { event =>
            (200, Json.toJson(event))
          }
          .recover {
            case e =>
              logger.error(e, "Error while marking variant displayed")
              (400,
               Json.obj("errors" -> "Error while marking variant displayed"))
          }
      )
      .getOrElse(FastFuture.successful((200, Json.obj())))

  def markVariantWonStringResp(experimentId: String,
                               clientId: String): Future[(Int, String)] =
    markVariantWon(experimentId, clientId).map {
      case (status, json) => (status, Json.stringify(json))
    }

}

///////////////////////////////////////////////////////////////////////
/////////////////////////   Features   ////////////////////////////////
///////////////////////////////////////////////////////////////////////

trait FeatureClient {

  def materializer: Materializer
  def izanamiDispatcher: IzanamiDispatcher

  def features(pattern: String): Future[Features]
  def features(pattern: String, context: JsObject): Future[Features]
  def checkFeature(key: String): Future[Boolean]
  def checkFeature(key: String, context: JsObject): Future[Boolean]

  def featureOrElse[T](key: String)(ok: => T)(ko: => T): Future[T] =
    checkFeature(key)
      .map {
        case true  => ok
        case false => ko
      }(izanamiDispatcher.ec)

  def featureOrElse[T](key: String, context: JsObject)(ok: => T)(
      ko: => T): Future[T] =
    checkFeature(key, context)
      .map {
        case true  => ok
        case false => ko
      }(izanamiDispatcher.ec)

  def onFeatureChanged(key: String)(cb: Feature => Unit): Registration =
    onEvent(key) {
      case FeatureCreated(id, f) if id == key =>
        cb(f)
      case FeatureUpdated(id, f, _) if id == key =>
        cb(f)
    }

  def onEvent(pattern: String)(cb: FeatureEvent => Unit): Registration = {
    val (killSwitch, done) = featuresSource(pattern)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.foreach(e => cb(e)))(Keep.both)
      .run()(materializer)
    DefaultRegistration(killSwitch, done)(izanamiDispatcher)
  }

  def featuresSource(pattern: String): Source[FeatureEvent, NotUsed]
  def featuresStream(pattern: String = "*"): Publisher[FeatureEvent]
}

trait Registration {
  def onComplete(cb: Try[Done] => Unit): Unit
  def close(): Unit
}

case class FakeRegistration() extends Registration {
  override def onComplete(cb: Try[Done] => Unit): Unit = {}
  override def close(): Unit = {}
}

case class DefaultRegistration(killSwitch: UniqueKillSwitch, done: Future[Done])(
    implicit izanamiDispatcher: IzanamiDispatcher
) extends Registration {

  override def onComplete(cb: Try[Done] => Unit): Unit =
    done.onComplete { cb }(izanamiDispatcher.ec)

  override def close(): Unit = killSwitch.shutdown()

}

object Features {

  def features(features: Feature*): ClientConfig => Features = { clientConfig =>
    Features(clientConfig, features, Seq.empty)
  }

  def empty(): ClientConfig => Features = { clientConfig =>
    Features(clientConfig, Seq.empty, Seq.empty)
  }

  def feature(key: String, active: Boolean) = DefaultFeature(key, active)

  def apply(features: Feature*): ClientConfig => Features =
    clientConfig => new Features(clientConfig, features, fallback = Seq.empty)

  def parseJson(json: String): ClientConfig => Features = {
    implicit val f = DefaultFeature.format
    val featuresSeq = Json
      .parse(json)
      .validate[Seq[DefaultFeature]]
      .fold(
        err =>
          throw IzanamiException(
            s"Error parsing json $json: \n${Json.prettyPrint(JsError.toJson(err))}"),
        identity
      )
    config =>
      Features(config, featuresSeq)
  }
}

case class Features(clientConfig: ClientConfig,
                    featuresSeq: Seq[Feature],
                    fallback: Seq[Feature] = Seq.empty) {
  def isActive(key: String): Boolean =
    featuresSeq
      .find(_.id == key)
      .map(_.isActive(clientConfig))
      .getOrElse(
        fallback.find(_.id == key).exists(_.isActive(clientConfig))
      )

  def tree(): JsObject = {
    val fallbackTree: JsObject =
      fallback.map(json).foldLeft(Json.obj())(_ deepMerge _)
    val featuresTree: JsObject =
      featuresSeq.map(json).foldLeft(Json.obj())(_ deepMerge _)
    fallbackTree deepMerge featuresTree
  }

  private def json(feature: Feature): JsObject = {
    val jsPath: JsPath = feature.id.split(":").foldLeft[JsPath](JsPath) {
      (p, s) =>
        p \ s
    }
    (jsPath \ "active").write[Boolean].writes(feature.isActive(clientConfig))
  }
}

///////////////////////////////////////////////////////////////////////
/////////////////////////    Configs    ///////////////////////////////
///////////////////////////////////////////////////////////////////////

object Config {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  private val read = (
    (__ \ "id").read[String] and
      (__ \ "value").read[String].map(s => Json.parse(s))
  )(Config.apply _)

  private val write: Writes[Config] = Writes[Config] { c =>
    Json.obj(
      "id" -> c.id,
      "value" -> Json.stringify(c.value)
    )
  }

  implicit val format = Format(read, write)

  val alternateRead = (
    (__ \ "id").read[String] and
      (__ \ "value").read[JsValue]
  )(Config.apply _)
}
case class Config(id: String, value: JsValue) {
  def asJson =
    id.split(":")
      .foldLeft[JsPath](JsPath)((path, segment) => path \ segment)
      .write[JsValue]
      .writes(value)
}

object Configs {

  def fromJson(configsJson: Seq[JsValue], fallback: Seq[Config]): Configs = {
    val configs = configsJson.flatMap(json => Config.format.reads(json).asOpt)
    Configs(configs, fallback)
  }

  def apply(configs: (String, JsValue)*): Configs =
    new Configs(configs.map(c => Config(c._1, c._2)), fallback = Seq.empty)

  def parseJson(json: String): Configs = {
    implicit val r = Config.alternateRead
    Configs(
      Json
        .parse(json)
        .validate[Seq[Config]]
        .fold(
          err =>
            throw IzanamiException(
              s"Error parsing json $json: \n${Json.prettyPrint(JsError.toJson(err))}"),
          identity
        )
    )
  }

}

case class Configs(configs: Seq[Config], fallback: Seq[Config] = Seq.empty) {

  def tree(): JsObject = {
    val fbTree = fallback
      .map(_.asJson)
      .foldLeft(Json.obj())(_ deepMerge _)
    val valuesTree = configs.map(_.asJson).foldLeft(Json.obj())(_ deepMerge _)
    fbTree deepMerge valuesTree
  }

  def get(key: String): JsValue =
    configs
      .find(_.id == key)
      .map(_.value)
      .getOrElse(
        fallback.find(_.id == key).map(_.value).getOrElse(Json.obj())
      )

}

sealed trait ConfigEvent extends Event {
  def id: String
}

object ConfigEvent {
  case class ConfigCreated(id: String, config: Config) extends ConfigEvent
  case class ConfigUpdated(id: String, config: Config, oldConfig: Config)
      extends ConfigEvent
  case class ConfigDeleted(id: String) extends ConfigEvent
}

trait ConfigClient {

  def materializer: Materializer
  def izanamiDispatcher: IzanamiDispatcher

  def configs(pattern: String = "*"): Future[Configs]
  def config(key: String): Future[JsValue]

  def onConfigChanged(key: String)(cb: JsValue => Unit): Registration =
    onEvent(key) {
      case ConfigCreated(id, c) if id == key =>
        cb(c.value)
      case ConfigUpdated(id, c, _) if id == key =>
        cb(c.value)
    }

  def onEvent(pattern: String)(cb: ConfigEvent => Unit): Registration = {
    val (killSwitch, done) = configsSource(pattern)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(Sink.foreach(e => cb(e)))(Keep.both)
      .run()(materializer)
    DefaultRegistration(killSwitch, done)(izanamiDispatcher)
  }

  def configsSource(pattern: String): Source[ConfigEvent, NotUsed]
  def configsStream(pattern: String = "*"): Publisher[ConfigEvent]

}

///////////////////////////////////////////////////////////////////////
/////////////////////////    Experiments    ///////////////////////////
///////////////////////////////////////////////////////////////////////

trait ExperimentsClient {

  def experiment(id: String): Future[Option[ExperimentClient]]

  def list(pattern: String): Future[Seq[ExperimentClient]]

  def tree(pattern: String, clientId: String): Future[JsObject]

  def getVariantFor(experimentId: String,
                    clientId: String): Future[Option[Variant]]

  def markVariantDisplayed(experimentId: String,
                           clientId: String): Future[ExperimentVariantDisplayed]

  def markVariantWon(experimentId: String,
                     clientId: String): Future[ExperimentVariantWon]
}

case class ExperimentClient(experimentsClient: ExperimentsClient,
                            experiment: Experiment) {

  def id: String = experiment.id
  def name: String = experiment.name
  def description: String = experiment.description
  def enabled: Boolean = experiment.enabled
  def variants: Seq[Variant] = experiment.variants

  def getVariantFor(clientId: String): Future[Option[Variant]] =
    experimentsClient.getVariantFor(experiment.id, clientId)

  def markVariantDisplayed(
      clientId: String): Future[ExperimentVariantDisplayed] =
    experimentsClient.markVariantDisplayed(experiment.id, clientId)

  def markVariantWon(clientId: String): Future[ExperimentVariantWon] =
    experimentsClient.markVariantWon(experiment.id, clientId)

}
