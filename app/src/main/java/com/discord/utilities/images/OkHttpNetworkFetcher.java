package com.discord.utilities.images;

import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.producers.BaseNetworkFetcher;
import com.facebook.imagepipeline.producers.BaseProducerContextCallbacks;
import com.facebook.imagepipeline.producers.Consumer;
import com.facebook.imagepipeline.producers.FetchState;
import com.facebook.imagepipeline.producers.NetworkFetcher;
import com.facebook.imagepipeline.producers.ProducerContext;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** OkHttp so uncached emoji are not stuck on Fresco's 3-thread HttpURLConnection pool. */
public final class OkHttpNetworkFetcher extends BaseNetworkFetcher<FetchState> {
    private final OkHttpClient client;

    public OkHttpNetworkFetcher() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(64);
        dispatcher.setMaxRequestsPerHost(16);
        client = new OkHttpClient.Builder().dispatcher(dispatcher).build();
    }

    @Override
    public FetchState createFetchState(Consumer<EncodedImage> consumer, ProducerContext context) {
        return new FetchState(consumer, context);
    }

    @Override
    public void fetch(FetchState fetchState, NetworkFetcher.Callback callback) {
        android.net.Uri uri = fetchState.getUri();
        if (uri == null) {
            callback.onFailure(new IOException("missing uri"));
            return;
        }
        Call call = client.newCall(new Request.Builder().url(uri.toString()).build());
        fetchState.getContext().addCallbacks(new BaseProducerContextCallbacks() {
            @Override
            public void onCancellationRequested() {
                call.cancel();
            }
        });
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) {
                    callback.onCancellation();
                } else {
                    callback.onFailure(e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (call.isCanceled()) {
                    response.close();
                    callback.onCancellation();
                    return;
                }
                if (!response.isSuccessful()) {
                    response.close();
                    callback.onFailure(new IOException("HTTP " + response.code()));
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    response.close();
                    callback.onFailure(new IOException("empty body"));
                    return;
                }
                try {
                    callback.onResponse(body.byteStream(), (int) body.contentLength());
                } catch (IOException e) {
                    callback.onFailure(e);
                } finally {
                    response.close();
                }
            }
        });
    }
}
