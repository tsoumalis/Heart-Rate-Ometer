package net.kibotu.heartrateometer;

import android.graphics.Color;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * This abstract class is used to process images.
 *
 * @author <a href="phishman3579@gmail.com">Justin Wetherell</a>
 */
public class MathHelper {
    private static float[] decodeYUV420SPtoRedSum(byte[] yuv420sp, int width, int height) {
        if (yuv420sp == null) {
            return new float[] { 0f, 0f, 0f, 0f };
        }

        final int frameSize = width * height;
        int sumRed = 0;
        int sumGreen = 0;
        int sumBlue = 0;

        for (int heightIndex = 0, pixelIndex = 0; heightIndex < height; heightIndex++) {
            int uvp = frameSize + (heightIndex >> 1) * width, u = 0, v = 0;

            for (int widthIndex = 0; widthIndex < width; widthIndex++, pixelIndex++) {
                int y = (0xff & yuv420sp[pixelIndex]) - 16;

                if (y < 0) {
                    y = 0;
                }

                if ((widthIndex & 1) == 0) {
                    v = (0xff & yuv420sp[uvp++]) - 128;
                    u = (0xff & yuv420sp[uvp++]) - 128;
                }

                int y1192 = 1192 * y;

                int redPixel = (y1192 + 1634 * v);
                if (redPixel < 0) {
                    redPixel = 0;
                } else if (redPixel > 262143) {
                    redPixel = 262143;
                }

                int greenPixel = (y1192 - 833 * v - 400 * u);
                if (greenPixel < 0) {
                    greenPixel = 0;
                } else if (greenPixel > 262143) {
                    greenPixel = 262143;
                }

                int bluePixel = (y1192 + 2066 * u);
                if (bluePixel < 0) {
                    bluePixel = 0;
                } else if (bluePixel > 262143) {
                    bluePixel = 262143;
                }

                int pixel = 0xff000000 | ((redPixel << 6) & 0xff0000) | ((greenPixel >> 2) & 0xff00)
                        | ((bluePixel >> 10) & 0xff);

                int red = (pixel >> 16) & 0xff;
                int green = (pixel >> 8) & 0xff;
                int blue = (pixel) & 0xff;

                sumRed += red;
                sumGreen += green;
                sumBlue += blue;
            }
        }

        return new float[] { sumRed, sumGreen, sumBlue };
    }

    public static float[] decodeYUV420SPtoRGBHAverage(byte[] yuv420sp, int width, int height) {
        if (yuv420sp == null) {
            return new float[] { 0f, 0f, 0f, 0f };
        }

        final int frameSize = width * height;

        float[] sum = decodeYUV420SPtoRedSum(yuv420sp, width, height);
        float[] toreturn = new float[3];
        for (int i = 0; i < sum.length; i++)
            toreturn[i] = sum[i] / frameSize;

        return toreturn;
    }
}
