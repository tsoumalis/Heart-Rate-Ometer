package net.kibotu.heartrateometer;

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

    //vvvvvvvvvvvv image processing vvvvvvvvvvvv

    private static int decodeYUV420SPtoRedSum(byte[] yuv420sp, int width, int height) {
        if (yuv420sp == null) {
            return 0;
        }

        final int frameSize = width * height;
        int sum1 = 0;
        int sum2 = 0;
        int sum3 = 0;
        int sum4 = 0;
        int widthMiddle = width / 2;
        int heightMiddle = height / 2;

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

                if (widthIndex < widthMiddle && heightIndex < heightMiddle)
                    sum1 += red;
                else if (widthIndex < widthMiddle && heightIndex > heightMiddle)
                    sum2 += red;
                else if (widthIndex > widthMiddle && heightIndex < heightMiddle)
                    sum3 += red;
                else if (widthIndex > widthMiddle && heightIndex > heightMiddle)
                    sum4 += red;
            }
        }

        //return Collections.max(Arrays.asList(sum1,sum2,sum3,sum4));
        //return sum1 + sum2 + sum3 + sum4;
        ArrayList<Integer> rates = new ArrayList<>(Arrays.asList(sum1, sum2, sum3, sum4));
        int max1 = Collections.max(rates);
        rates.remove((Object)max1);
        return max1 + Collections.max(rates);
    }

    public static int decodeYUV420SPtoRedAvg(byte[] yuv420sp, int width, int height) {
        if (yuv420sp == null) {
            return 0;
        }

        final int frameSize = width * height;

        int sum = decodeYUV420SPtoRedSum(yuv420sp, width, height);

        //Log.d("RED", "decodeYUV420SPtoRedAvg: " + sum);
        return (sum / (frameSize / 2));
        //return sum / frameSize;
    }

    //^^^^^^^^^^^^ image processing ^^^^^^^^^^^^
}
