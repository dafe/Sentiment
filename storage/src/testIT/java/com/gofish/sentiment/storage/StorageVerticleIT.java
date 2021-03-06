package com.gofish.sentiment.storage;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.RunTestOnContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.types.EventBusService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author Luke Herron
 */
@RunWith(VertxUnitRunner.class)
public class StorageVerticleIT {

    @Rule
    public final RunTestOnContext vertxRule = new RunTestOnContext();

    private Vertx vertx;
    private ServiceDiscovery serviceDiscovery;
    private StorageService storageService;

    private String serviceDiscoveryStatus = "";

    @Before
    public void setUp(TestContext context) {
        vertx = vertxRule.vertx();
        vertx.exceptionHandler(context.exceptionHandler());
        serviceDiscovery = ServiceDiscovery.create(vertx);

        JsonObject command = new JsonObject()
                .put("dropDatabase", 1);

        // We'll start with a clean DB on each run
        MongoClient.createNonShared(vertx, new JsonObject())
                .runCommand("dropDatabase", command, context.asyncAssertSuccess());

        vertx.eventBus().<JsonObject>consumer("vertx.discovery.announce", messageHandler -> {
            JsonObject announce = messageHandler.body();
            serviceDiscoveryStatus = announce.getString("status");
        });

        vertx.deployVerticle(StorageVerticle.class.getName(), context.asyncAssertSuccess(result -> {
            EventBusService.getProxy(serviceDiscovery, StorageService.class, context.asyncAssertSuccess(storageService -> {
                this.storageService = storageService;
            }));
        }));
    }

    @Test
    public void testStorageServicePublishedStatus(TestContext context) {
        context.assertEquals(serviceDiscoveryStatus, "UP", "Service Discovery Status returned '" +
                serviceDiscoveryStatus + "'. Expected 'UP'");
    }

    @Test
    public void testStorageServiceUnpublishedStatus(TestContext context) {
        vertx.close(completionHandler -> {
            context.assertEquals(serviceDiscoveryStatus, "DOWN", "Service Discovery Status returned '" +
                    serviceDiscoveryStatus + "'. Expected 'DOWN'");
        });
    }

    @Test
    public void testPublishedStorageServiceNotNull(TestContext context) {
        context.assertNotNull(storageService);
    }

    @Test
    public void testStorageServiceProxyNotNull(TestContext context) {
        StorageService service = StorageService.createProxy(vertx, StorageService.ADDRESS);
        context.assertNotNull(service);
    }

    @Test
    public void testStorageServiceCanGetCollections(TestContext context) {
        storageService.getCollections(context.asyncAssertSuccess(context::assertNotNull));

        storageService.createCollection("testCollection", context.asyncAssertSuccess(result ->
                storageService.getCollections(context.asyncAssertSuccess(collections ->
                        context.assertTrue(collections.size() > 0)))));
    }

    @Test
    public void testStorageServiceProxyCanGetCollections(TestContext context) {
        StorageService service = StorageService.createProxy(vertx, StorageService.ADDRESS);
        context.assertNotNull(service);

        service.getCollections(context.asyncAssertSuccess(context::assertNotNull));
    }

    @Test
    public void testStorageServiceCanCheckCollectionExists(TestContext context) {
        storageService.hasCollection("collectionThatDoesNotExist", context.asyncAssertSuccess(context::assertFalse));
        storageService.hasCollection(null, context.asyncAssertSuccess(context::assertFalse));

        storageService.createCollection("testCollection", context.asyncAssertSuccess(result -> {
            storageService.hasCollection("testCollection", context.asyncAssertSuccess(context::assertTrue));
        }));
    }

    @Test
    public void testStorageServiceProxyCanCheckCollectionExists(TestContext context) {
        StorageService service = StorageService.createProxy(vertx, StorageService.ADDRESS);
        context.assertNotNull(service);

        service.hasCollection("collectionThatDoesNotExist", context.asyncAssertSuccess(context::assertFalse));
        service.hasCollection(null, context.asyncAssertSuccess(context::assertFalse));

        service.createCollection("testCollection", context.asyncAssertSuccess(result -> {
            service.hasCollection("testCollection", context.asyncAssertSuccess(context::assertTrue));
        }));
    }

    @Test
    public void testStorageServiceCanCreateCollection(TestContext context) {
        storageService.createCollection("test", context.asyncAssertSuccess(result -> {
            storageService.hasCollection("test", context.asyncAssertSuccess(context::assertTrue));
        }));
    }

    @Test
    public void testStorageServiceProxyCanCreateCollection(TestContext context) {
        StorageService service = StorageService.createProxy(vertx, StorageService.ADDRESS);
        context.assertNotNull(service);

        service.createCollection("proxyTest", context.asyncAssertSuccess(result -> {
            service.hasCollection("proxyTest", context.asyncAssertSuccess(context::assertTrue));
        }));
    }

    @Test
    public void testStorageServiceCanCreateIndex(TestContext context) {
        final JsonObject collectionIndex = new JsonObject()
                .put("name", 1)
                .put("datePublished", 1)
                .put("description", 1);

        storageService.isIndexPresent(
                "createIndexTestCollectionIndex",
                "createIndexTestCollection",
                context.asyncAssertSuccess(isPresent -> {
                    context.assertFalse(isPresent); // Index should not yet exist, as it has not yet been created
                    storageService.createIndex("createIndexTestCollection", collectionIndex, context.asyncAssertSuccess(v -> {
                        storageService.isIndexPresent(
                                "createIndexTestCollectionIndex",
                                "createIndexTestCollection",
                                context.asyncAssertSuccess(context::assertTrue) // Index should now exist
                        );

                        storageService.hasCollection("createIndexTestCollection", context.asyncAssertSuccess(context::assertTrue));
                    }));
                })
        );
    }

    @Test
    public void testStorageServiceProxyCanCreateIndex(TestContext context) {
        final JsonObject collectionIndex = new JsonObject()
                .put("name", 1)
                .put("datePublished", 1)
                .put("description", 1);

        StorageService service = StorageService.createProxy(vertx, StorageService.ADDRESS);
        context.assertNotNull(service);

        service.isIndexPresent(
                "proxyCreateIndexTestCollectionIndex",
                "proxyCreateIndexTestCollection",
                context.asyncAssertSuccess(isPresent -> {
                    context.assertFalse(isPresent); // Index should not yet exist, as it has not yet been created
                    service.createIndex("proxyCreateIndexTestCollection", collectionIndex, context.asyncAssertSuccess(v -> {
                        service.isIndexPresent(
                                "proxyCreateIndexTestCollectionIndex",
                                "proxyCreateIndexTestCollection",
                                context.asyncAssertSuccess(context::assertTrue) // Index should now exist
                        );

                        // If a collection does not exist when creating the index, it should be created automatically
                        service.hasCollection("proxyCreateIndexTestCollection", context.asyncAssertSuccess(context::assertTrue));
                    }));
                })
        );
    }

    @Test
    public void testStorageServiceCanSaveArticles(TestContext context) {
        final JsonArray articles = new JsonArray()
                .add(new JsonObject().put("article1", "testArticle"))
                .add(new JsonObject().put("article2", "testArticle"))
                .add(new JsonObject().put("article3", "testArticle"));

        storageService.saveArticles("saveArticleCollection", articles, context.asyncAssertSuccess(result -> {
            context.assertEquals(1, result.getInteger("ok"));
            context.assertEquals(3, result.getInteger("n"));
        }));
    }

    @Test
    public void testStorageServiceProxyCanSaveArticles(TestContext context) {
        final JsonArray articles = new JsonArray()
                .add(new JsonObject().put("article1", "testArticle"))
                .add(new JsonObject().put("article2", "testArticle"))
                .add(new JsonObject().put("article3", "testArticle"));

        StorageService service = StorageService.createProxy(vertx, StorageService.ADDRESS);
        context.assertNotNull(service);

        service.saveArticles("proxySaveArticleCollection", articles, context.asyncAssertSuccess(result -> {
            context.assertEquals(1, result.getInteger("ok"));
            context.assertEquals(3, result.getInteger("n"));
        }));
    }

    @Test
    public void testStorageServiceFailsSavingInvalidArticle(TestContext context) {
        storageService.saveArticles("saveInvalidArticleCollection", null, context.asyncAssertFailure());
        storageService.saveArticles("saveInvalidArticleCollection", new JsonArray(), context.asyncAssertFailure());
    }

    @Test
    public void testStorageServiceProxyFailsSavingInvalidArticle(TestContext context) {
        StorageService service = StorageService.createProxy(vertx, StorageService.ADDRESS);
        context.assertNotNull(service);

        service.saveArticles("saveInvalidArticleCollection", null, context.asyncAssertFailure());
        service.saveArticles("saveInvalidArticleCollection", new JsonArray(), context.asyncAssertFailure());
    }

    @Test
    public void testStorageServiceCanGetSentimentResult(TestContext context) {
        storageService.getSentimentResults("testSentimentCollection", context.asyncAssertSuccess(result -> {
            context.assertTrue(result.isEmpty());
        }));
    }

    @Test
    public void testStorageServiceProxyCanGetSentimentResult(TestContext context) {
        StorageService service = StorageService.createProxy(vertx, StorageService.ADDRESS);
        context.assertNotNull(service);

        service.getSentimentResults("testSentimentCollection", context.asyncAssertSuccess(result -> {
            context.assertTrue(result.isEmpty());
        }));
    }
}
