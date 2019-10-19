/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
package ai.djl.examples.inference;

import ai.djl.examples.inference.util.AbstractExample;
import ai.djl.examples.inference.util.Arguments;
import ai.djl.examples.util.MemoryUtils;
import ai.djl.inference.Predictor;
import ai.djl.metric.Metrics;
import ai.djl.modality.Classification;
import ai.djl.modality.cv.util.BufferedImageUtils;
import ai.djl.mxnet.zoo.MxModelZoo;
import ai.djl.translate.TranslateException;
import ai.djl.zoo.ModelNotFoundException;
import ai.djl.zoo.ZooModel;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ActionRecognition extends AbstractExample {

    public static void main(String[] args) {
        new ActionRecognition().runExample(args);
    }

    @Override
    protected Classification predict(Arguments arguments, Metrics metrics, int iteration)
            throws IOException, ModelNotFoundException, TranslateException {

        Classification result;
        Path imageFile = arguments.getImageFile();
        BufferedImage img = BufferedImageUtils.fromFile(imageFile);
        Map<String, String> criteria = new ConcurrentHashMap<>();
        criteria.put("backbone", "inceptionv3");
        criteria.put("dataset", "ucf101");
        ZooModel<BufferedImage, Classification> inception =
                MxModelZoo.ACTION_RECOGNITION.loadModel(criteria);

        try (Predictor<BufferedImage, Classification> action = inception.newPredictor()) {
            action.setMetrics(metrics); // Let predictor collect metrics
            result = action.predict(img);

            MemoryUtils.collectMemoryInfo(metrics);
        }

        inception.close();
        return result;
    }
}
