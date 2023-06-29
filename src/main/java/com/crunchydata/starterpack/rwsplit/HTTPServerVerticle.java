package com.crunchydata.starterpack.rwsplit;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
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

    router.route().handler(BodyHandler.create());
    // Setup Prometheus
    router.get("/metrics").handler(PrometheusScrapingHandler.create());

    // Install handlers
    //untested
    router.get("/api/user/:id").handler(rc -> {
      // Execute with the RO pool
      roPool.preparedQuery("SELECT * FROM acme_user WHERE id=$1")
        .execute(Tuple.of(Integer.parseInt(rc.pathParam("id"))))
        .onSuccess(rs -> {
          if (rs.size() > 0) {
            rs.forEach(row -> {
              rc.end(row.toJson().encode());
            });
          } else {
            rc.end("{}");
          }
        })
        .onFailure(t->{
          logger.info(t.getMessage());
          rc.fail(500);
        });
    });
    //untested
    router.post("/api/user/:id/email").handler(rc -> {
      // Execute with the RW pool
      logger.info(rc.queryParams().get("email"));
      logger.info(rc.pathParams().get("email"));
      logger.info(rc.get("email"));
//      logger.info(rc.body().asJsonObject().encode());
      logger.info(rc.data().toString());
      logger.info(rc.pathParam("id"));
      rwPool.preparedQuery("UPDATE acme_user SET email=$1 WHERE id=$2 RETURNING *")
        .execute(Tuple.of(rc.pathParam("email"),Integer.parseInt(rc.pathParam("id"))))
        .onSuccess(rs -> {
          if (rs.size() == 1) {
            rs.forEach(row -> {
              rc.end(row.toJson().encode());
            });
          } else {
            rc.end("{}");
          }
        })
        .onFailure(t->{
          rc.fail(500);
        });
    });

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
}
