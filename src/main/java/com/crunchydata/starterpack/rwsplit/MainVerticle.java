package com.crunchydata.starterpack.rwsplit;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.pgclient.SslMode;
import io.vertx.pgclient.impl.PgPoolOptions;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Matt Hudson
 * @version 1.0
 * <p>Simple superclass and deployer for Verticles</p>
 * @since June 17, 2023
 */
public class MainVerticle extends AbstractVerticle {
  private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);
  protected PgPool rwPool;
  protected PgPool roPool;

  public MainVerticle() {
  }

  public MainVerticle(PgPool rwPool, PgPool roPool) {
    this.rwPool = rwPool;
    this.roPool = roPool;
  }

  @Override
  public void start(Promise<Void> startPromise) {

    // Without this, cwd will be calculated inside the jar so the default path will be relative to the CLASSPATH
    ConfigStoreOptions fileStore = new ConfigStoreOptions()
      .setType("file")
      .setFormat("json")
      .setConfig(new JsonObject().put("path","conf/config.json"));
    ConfigStoreOptions envStore = new ConfigStoreOptions()
      .setType("env");
    ConfigRetrieverOptions retrieverOptions = new ConfigRetrieverOptions()
      .addStore(envStore)
      .addStore(fileStore);

    // Without retrieverOptions this reads from the classpath.
    ConfigRetriever retriever = ConfigRetriever.create(vertx, retrieverOptions);

    // Load config from disk
    retriever.getConfig()
      .compose(fullCfg -> {

        // Merge config into defaults
        JsonObject cfg = getCfg(fullCfg);

        DeploymentOptions deploymentOptions = new DeploymentOptions()
          .setConfig(cfg);

        // DB setup

        // Create common pool configuration
        PoolOptions poolOptions = new PgPoolOptions(new PoolOptions())
          .setMaxSize(cfg.getInteger("maxPoolSize"))
          .setName(cfg.getString("appName"));

        // Configure RW connection
        PgConnectOptions rwConnectOptions = PgConnectOptions.fromUri(cfg.getString("rwPgURI"))
          .setSslMode(SslMode.REQUIRE)
          .setMetricsName(cfg.getString("rw_pgMetricsName"))
          .setTrustAll(true);// This is required to disable cert chain validation (we don't have the CA root)

        // Configure RO connection
        PgConnectOptions roConnectOptions = PgConnectOptions.fromUri(cfg.getString("roPgURI"))
          .setSslMode(SslMode.REQUIRE)
          .setMetricsName(cfg.getString("ro_pgMetricsName"))
          .setTrustAll(true);// This is required to disable cert chain validation (we don't have the CA root)

        // Create RW pool
        rwPool = PgPool.pool(vertx, rwConnectOptions, poolOptions);

        // Create RO pool
        rwPool = PgPool.pool(vertx, roConnectOptions, poolOptions);

        // Broadcast config changes
        retriever.listen(configChange -> {
          JsonObject newCfg = getCfg(configChange.getNewConfiguration());
          if (! newCfg.encode().equals(getCfg(configChange.getPreviousConfiguration()).encode()))
            vertx.eventBus().send(cfg.getString("configChannel"), newCfg);
        });

        // Recycling this app pattern? Start chopping here

        // Start web server
        return vertx.deployVerticle(new HTTPServerVerticle(rwPool, roPool), deploymentOptions);
      })
      .onSuccess(ns -> {
        startPromise.complete();
      })
      .onFailure(t -> {
        startPromise.fail(t);
      });

}

  private static JsonObject getCfg(JsonObject fullCfg) {
    return fullCfg.getJsonObject("default").mergeIn(
      fullCfg.getJsonObject(
        fullCfg.getString(
          fullCfg.getString("appEnv"))));
  }

  protected String getURL(String webkey) {
    return vertx.getOrCreateContext().config().getString("webUrlPrefix") + "/" + webkey;
  }
}
