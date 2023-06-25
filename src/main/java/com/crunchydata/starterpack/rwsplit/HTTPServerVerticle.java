package com.crunchydata.starterpack.rwsplit;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.micrometer.PrometheusScrapingHandler;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Matt Hudson
 * @version 1.0
 * <p>HTTPServerVerticle starts a REST API server</p
 * @since June 17, 2023
 */
public class HTTPServerVerticle extends MainVerticle {
  private static final Logger logger = LoggerFactory.getLogger(HTTPServerVerticle.class);
  protected HttpServer httpServer;

  public HTTPServerVerticle(PgPool rwPool, PgPool roPool) {
    super(rwPool, roPool);
  }

  @Override
  public void start(Promise<Void> startPromise) {
    JsonObject cfg = vertx.getOrCreateContext().config();

    // Subscribe to config change events
    vertx.eventBus().consumer(cfg.getString("configChannel"), message -> {
      // redeploy
      JsonObject newCfg = (JsonObject) message.body();
      if (httpServer != null)
        httpServer.close()
          .onComplete(v -> startServer(Promise.promise(), newCfg));
      else
        startServer(Promise.promise(), newCfg);
    });

    startServer(startPromise, cfg);
  }

  private void startServer(Promise<Void> startPromise, JsonObject cfg) {
    Router router = Router.router(vertx);

    // Setup Prometheus
    router.get("/metrics").handler(PrometheusScrapingHandler.create());

    // Install handlers
    router.get(cfg.getString("webRoute")).handler(getSampleROHandler());

    // Start web server
    httpServer = vertx.createHttpServer();
    httpServer.requestHandler(router)
      .listen(cfg.getInteger("httpPort"), cfg.getString("listenAddress"))
      .compose(http -> {
        logger.info(String.format("HTTP server started on port %s:%s", cfg.getString("listenAddress"), cfg.getInteger("httpPort")));
        startPromise.complete();
        return Future.succeededFuture();
      }).onFailure(startPromise::fail);
  }

  private Handler<RoutingContext> getSampleROHandler() {
    return req -> {
      String webkey = req.pathParam("webkey");
      roPool.preparedQuery(vertx.getOrCreateContext().config().getString("readQuery")).execute(Tuple.of(webkey))
        .compose(rowSet -> {
          if (rowSet.size() > 0) {
            req.response().putHeader("content-type", "text/plain");
            req.response().end(rowSet.iterator().next().getString(vertx.getOrCreateContext().config().getString("pasteColumn")));
          } else {
            req.response().putHeader("content-type", "text/plain").end("webkey not found");
          }
          return Future.succeededFuture();
        }).onFailure(t -> {
          if (logger.isDebugEnabled())
            logger.debug(t.toString());
        });
    };
  }
}
