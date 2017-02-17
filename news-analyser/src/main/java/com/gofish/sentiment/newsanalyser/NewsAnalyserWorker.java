package com.gofish.sentiment.newsanalyser;

import com.gofish.sentiment.common.http.ResponseHandler;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.rxjava.core.AbstractVerticle;
import io.vertx.rxjava.core.buffer.Buffer;
import io.vertx.rxjava.core.eventbus.MessageConsumer;
import io.vertx.rxjava.core.http.HttpClient;
import io.vertx.rxjava.core.http.HttpClientRequest;
import rx.Observable;

import java.util.Optional;
import java.util.UUID;

/**
 * @author Luke Herron
 */
public class NewsAnalyserWorker extends AbstractVerticle {

    public static final String ADDRESS = "sentiment.analyser.worker";
    private static final Logger LOG = LoggerFactory.getLogger(NewsAnalyserWorker.class);

    private HttpClient httpClient;
    private MessageConsumer<JsonObject> messageConsumer;

    private String apiKey;
    private String baseUrl;
    private String urlPath;
    private Integer port;

    public NewsAnalyserWorker() {
        // Vertx requires a default constructor
    }

    public NewsAnalyserWorker(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public void start() throws Exception {
        LOG.info("Bringing up News Analyser Worker");

        JsonObject apiConfig = Optional.ofNullable(config().getJsonObject("api"))
                .orElseThrow(() -> new RuntimeException("Could not load analyser configuration"));

        apiKey = apiConfig.getString("key", "");
        baseUrl = apiConfig.getString("base.url", "");
        urlPath = apiConfig.getString("url.path", "");
        port = apiConfig.getInteger("port", 443);
        httpClient = Optional.ofNullable(httpClient).orElseGet(() -> vertx.createHttpClient(getHttpClientOptions()));

        messageConsumer = vertx.eventBus().localConsumer(ADDRESS, messageHandler -> {
            try {
                final JsonObject article = messageHandler.body().getJsonObject("article");
                final String analysisText = String.join(". ", article.getString("name"), article.getString("description"));
                final JsonObject requestData = new JsonObject().put("documents", new JsonArray()
                        .add(new JsonObject()
                                .put("language", "en")
                                .put("id", UUID.randomUUID().toString())
                                .put("text", analysisText)));

                final Buffer chunk = Buffer.buffer(requestData.encode());

                HttpClientRequest request = httpClient.request(HttpMethod.POST, port, baseUrl, urlPath)
                        .putHeader("Content-Type", "application/json; charset=UTF-8")
                        .putHeader("Content-Length", String.valueOf(chunk.length()))
                        .putHeader("Ocp-Apim-Subscription-Key", apiKey);

                LOG.info("Calling Text Analytics API");

                request.toObservable()
                        .flatMap(ResponseHandler::handle)
                        .doOnNext(result -> LOG.debug(result.encodePrettily()))
                        .flatMap(result -> this.addSentimentResults(article, result))
                        .subscribe(
                                result -> messageHandler.reply(result),
                                failure -> messageHandler.fail(1, failure.getMessage()),
                                () -> {
                                    // request.end() must occur inside onComplete to avoid 'Request already complete'
                                    // exceptions which may occure if initial request fails and a retry is necessary
                                    request.end();
                                    LOG.info("Finished News Analysis");
                                }
                        );

                request.write(chunk);
            }
            catch (Throwable t) {
                LOG.error(t.getMessage(), t.getCause());
                messageHandler.fail(2, "Invalid Request");
            }
        });
    }

    private Observable<JsonObject> addSentimentResults(JsonObject article, JsonObject analysisResponse) {
        article.put("sentiment", analysisResponse.getJsonArray("documents").getJsonObject(0));

        return Observable.just(article);
    }

    /**
     * Creates and returns an HttpClientOptions object. Values for each option are retrieved from the config json object
     * (this config json object is passed in to the verticle when it is deployed)
     *
     * @return HttpClientOptions object to configure this verticles HttpClient
     */
    private HttpClientOptions getHttpClientOptions() {
        return new HttpClientOptions()
                .setPipelining(true)
                .setPipeliningLimit(8)
                .setIdleTimeout(0)
                .setSsl(true)
                .setKeepAlive(true);
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
        httpClient.close();
        messageConsumer.unregisterObservable().subscribe(
                stopFuture::complete,
                stopFuture::fail,
                () -> LOG.info("NewsAnalyserWorker messageConsumer unregistered")
        );
    }
}