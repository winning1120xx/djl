/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.serving.http;

import ai.djl.ModelException;
import ai.djl.modality.Input;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.serving.util.NettyUtils;
import ai.djl.serving.wlm.Job;
import ai.djl.serving.wlm.ModelInfo;
import ai.djl.serving.wlm.ModelManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A class handling inbound HTTP requests for the management API. */
public class InferenceRequestHandler extends HttpRequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(InferenceRequestHandler.class);

    private static final Pattern PATTERN =
            Pattern.compile("^/(ping|invocations|predictions)([/?].*)?");

    /** {@inheritDoc} */
    @Override
    public boolean acceptInboundMessage(Object msg) throws Exception {
        if (super.acceptInboundMessage(msg)) {
            FullHttpRequest req = (FullHttpRequest) msg;
            return PATTERN.matcher(req.uri()).matches();
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    protected void handleRequest(
            ChannelHandlerContext ctx,
            FullHttpRequest req,
            QueryStringDecoder decoder,
            String[] segments)
            throws ModelException {
        switch (segments[1]) {
            case "ping":
                ModelManager.getInstance().workerStatus(ctx);
                break;
            case "invocations":
                handleInvocations(ctx, req, decoder);
                break;
            case "predictions":
                handlePredictions(ctx, req, segments);
                break;
            default:
                throw new AssertionError("Invalid request uri: " + req.uri());
        }
    }

    private void handlePredictions(
            ChannelHandlerContext ctx, FullHttpRequest req, String[] segments)
            throws ModelNotFoundException {
        if (segments.length < 3) {
            throw new ResourceNotFoundException();
        }
        predict(ctx, req, null, segments[2]);
    }

    private void handleInvocations(
            ChannelHandlerContext ctx, FullHttpRequest req, QueryStringDecoder decoder)
            throws ModelNotFoundException {
        String modelName = NettyUtils.getParameter(decoder, "model_name", null);
        if ((modelName == null || modelName.isEmpty())) {
            if (ModelManager.getInstance().getStartupModels().size() == 1) {
                modelName = ModelManager.getInstance().getStartupModels().iterator().next();
            }
        }
        predict(ctx, req, decoder, modelName);
    }

    private void predict(
            ChannelHandlerContext ctx,
            FullHttpRequest req,
            QueryStringDecoder decoder,
            String modelName)
            throws ModelNotFoundException {
        Input input = parseRequest(ctx, req, decoder);
        if (modelName == null) {
            throw new BadRequestException("Parameter model_name is required.");
        }

        if (HttpMethod.OPTIONS.equals(req.method())) {
            ModelManager modelManager = ModelManager.getInstance();
            ModelInfo model = modelManager.getModels().get(modelName);
            if (model == null) {
                throw new ModelNotFoundException("Model not found: " + modelName);
            }

            NettyUtils.sendJsonResponse(ctx, "{}");
            return;
        }

        Job job = new Job(ctx, modelName, input);
        if (!ModelManager.getInstance().addJob(job)) {
            throw new ServiceUnavailableException(
                    "No worker is available to serve request: " + modelName);
        }
    }

    private static Input parseRequest(
            ChannelHandlerContext ctx, FullHttpRequest req, QueryStringDecoder decoder) {
        String requestId = NettyUtils.getRequestId(ctx.channel());
        Input input = new Input(requestId);
        if (decoder != null) {
            for (Map.Entry<String, List<String>> entry : decoder.parameters().entrySet()) {
                String key = entry.getKey();
                for (String value : entry.getValue()) {
                    input.addData(key, value.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        CharSequence contentType = HttpUtil.getMimeType(req);
        for (Map.Entry<String, String> entry : req.headers().entries()) {
            input.addProperty(entry.getKey(), entry.getValue());
        }

        if (HttpPostRequestDecoder.isMultipart(req)
                || HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.contentEqualsIgnoreCase(
                        contentType)) {
            HttpDataFactory factory = new DefaultHttpDataFactory(6553500);
            HttpPostRequestDecoder form = new HttpPostRequestDecoder(factory, req);
            try {
                while (form.hasNext()) {
                    NettyUtils.addFormData(form.next(), input);
                }
            } catch (HttpPostRequestDecoder.EndOfDataDecoderException ignore) {
                logger.trace("End of multipart items.");
            } finally {
                form.cleanFiles();
                form.destroy();
            }
        } else {
            byte[] content = NettyUtils.getBytes(req.content());
            input.addData("body", content);
        }
        return input;
    }
}
