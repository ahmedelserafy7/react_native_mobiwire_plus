// Mobiprint3plusModule.java

package com.mm.treka.mobiprint3plus;

import android.content.Context;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReadableArray;
import com.mobiwire.CSAndroidGoLib.AndroidGoCSApi;
import com.mobiwire.CSAndroidGoLib.CsPrinter;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Mobiprint3plusModule extends ReactContextBaseJavaModule {

    private final ReactApplicationContext reactContext;
    private CsPrinter printer;
    private Bitmap pendingBitmap;

    public Mobiprint3plusModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "Mobiprint3plus";
    }

    @ReactMethod
    public void printMobiwirePrinter(final String imageUrl, final ReadableArray firstRegularHeader,
            final ReadableArray boldHeader, final ReadableArray secondRegularHeader,
            final ReadableArray contentRows, final ReadableArray footerData,
            final String qrUrl, final String qrDisclaimer,
            final ReadableArray boldLineIndicesArray, final ReadableArray sectionSizesArray) {

        Log.d("Mobiprint3plus", "Unified Perfect-Fit print command received");
        final Context context = this.reactContext.getCurrentActivity();
        final Context useCtx = (context != null) ? context : reactContext;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. Logo Handling (Immediate)
                    if (imageUrl != null && !imageUrl.isEmpty()) {
                        Bitmap logo = downloadAndProcessBitmap(imageUrl);
                        if (logo != null) {
                            CsPrinter.printBitmap(useCtx, logo);
                            Thread.sleep(500);
                        }
                    }

                    // 2. Full Receipt Body (ONE UNIFIED BITMAP)
                    // We render everything from the headers down to the QR into one image
                    // to completely bypass all intermediate hardware "jumps" (feeds).
                    final int paperWidth = 384;
                    final int rowGap = 12; // Increased for visibility
                    final int sectionDividerGap = 16;
                    int[] boldLineIndices = parseIntArray(boldLineIndicesArray);

                    java.util.ArrayList<Bitmap> bodyBitmaps = new java.util.ArrayList<>();

                    // A. Add Headers to the bitmap sequence
                    String dividerStr = ". . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .";
                    if (firstRegularHeader != null) {
                        for (int i = 0; i < firstRegularHeader.size(); i++) {
                            String text = firstRegularHeader.getString(i);
                            if (text != null && !text.trim().isEmpty()) {
                                bodyBitmaps.add(renderTextLineToBitmap(text, paperWidth, 24, false));
                            }
                        }
                    }
                    if (boldHeader != null) {
                        for (int i = 0; i < boldHeader.size(); i++) {
                            String text = boldHeader.getString(i);
                            if (text != null && !text.trim().isEmpty()) {
                                bodyBitmaps.add(renderTextLineToBitmap(text, paperWidth, 25, true));
                            }
                        }
                    }
                    if (secondRegularHeader != null) {
                        for (int i = 0; i < secondRegularHeader.size(); i++) {
                            String text = secondRegularHeader.getString(i);
                            if (text != null && !text.trim().isEmpty()) {
                                bodyBitmaps.add(renderTextLineToBitmap(text, paperWidth, 24, false));
                            }
                        }
                    }

                    // Divider before content + Spacer
                    bodyBitmaps.add(renderTextLineToBitmap(dividerStr, paperWidth, 24, false));
                    Bitmap firstSpacer = Bitmap.createBitmap(paperWidth, 6, Bitmap.Config.RGB_565);
                    firstSpacer.eraseColor(Color.WHITE);
                    bodyBitmaps.add(firstSpacer);

                    // B. Add Transaction Content Rows
                    if (contentRows != null && contentRows.size() > 0) {
                        int currentRowInCurrentSection = 0;
                        int sectionIdx = 0;

                        for (int i = 0; i < contentRows.size(); i++) {
                            String rowData = contentRows.getString(i);
                            if (rowData == null || rowData.trim().isEmpty())
                                continue;

                            boolean isBold = isBoldLine(i, boldLineIndices);
                            Bitmap rowBmp = renderTextLineToBitmap(rowData, paperWidth, 24, isBold);
                            if (rowBmp != null) {
                                bodyBitmaps.add(rowBmp);
                                currentRowInCurrentSection++;
                            }

                            // 1. Handle Section Dividers (Lines)
                            boolean addedSectionDivider = false;
                            if (sectionSizesArray != null) {
                                int targetSize = (sectionIdx < sectionSizesArray.size())
                                        ? sectionSizesArray.getInt(sectionIdx)
                                        : 2;
                                if (currentRowInCurrentSection >= targetSize) {
                                    bodyBitmaps.add(renderTextLineToBitmap(dividerStr, paperWidth, 24, false));

                                    Bitmap postDividerSpacer = Bitmap.createBitmap(paperWidth, 10,
                                            Bitmap.Config.RGB_565);
                                    postDividerSpacer.eraseColor(Color.WHITE);
                                    bodyBitmaps.add(postDividerSpacer);

                                    currentRowInCurrentSection = 0;
                                    sectionIdx++;
                                    addedSectionDivider = true;
                                }
                            }

                            // 2. Handle Row Spacers (Focus: Spacers, not dividers!!)
                            if (!addedSectionDivider && i < contentRows.size() - 1) {
                                Bitmap spacer = Bitmap.createBitmap(paperWidth, rowGap, Bitmap.Config.RGB_565);
                                spacer.eraseColor(Color.WHITE);
                                bodyBitmaps.add(spacer);
                            }
                        }
                        // Final divider
                        if (currentRowInCurrentSection > 0) {
                            bodyBitmaps.add(renderTextLineToBitmap(dividerStr, paperWidth, 24, false));
                        }
                    }

                    // C. Add QR Code
                    if (qrUrl != null && !qrUrl.trim().isEmpty()) {
                        Bitmap spacer = Bitmap.createBitmap(paperWidth, sectionDividerGap, Bitmap.Config.RGB_565);
                        spacer.eraseColor(Color.WHITE);
                        bodyBitmaps.add(spacer);

                        Bitmap qrBmp = generateCustomQRCode(qrUrl, 150, 150);
                        if (qrBmp != null) {
                            bodyBitmaps.add(qrBmp);
                            if (qrDisclaimer != null && !qrDisclaimer.trim().isEmpty()) {
                                Bitmap discSpacer = Bitmap.createBitmap(paperWidth, 12, Bitmap.Config.RGB_565);
                                discSpacer.eraseColor(Color.WHITE);
                                bodyBitmaps.add(discSpacer);

                                Bitmap discBmp = renderTextLineToBitmap(qrDisclaimer, paperWidth, 22, false);
                                if (discBmp != null) {
                                    bodyBitmaps.add(discBmp);
                                    
                                    // Add spacing line (divider) after disclaimer
                                    Bitmap postDiscSpacer = Bitmap.createBitmap(paperWidth, 8, Bitmap.Config.RGB_565);
                                    postDiscSpacer.eraseColor(Color.WHITE);
                                    bodyBitmaps.add(postDiscSpacer);
                                }
                            }
                        }
                    }

                    // Stitch and Print Everything (Unified Pass)
                    if (!bodyBitmaps.isEmpty()) {
                        int totalHeight = 0;
                        for (Bitmap b : bodyBitmaps)
                            totalHeight += b.getHeight();
                        totalHeight += 10;

                        Bitmap combined = Bitmap.createBitmap(paperWidth, totalHeight, Bitmap.Config.RGB_565);
                        combined.eraseColor(Color.WHITE);
                        Canvas canvas = new Canvas(combined);
                        int currentY = 0;
                        for (Bitmap b : bodyBitmaps) {
                            float left = (paperWidth - b.getWidth()) / 2f;
                            canvas.drawBitmap(b, left, currentY, null);
                            currentY += b.getHeight();
                        }
                        Log.d("Mobiprint3plus", "Printing unified Perfect-Fit pass...");
                        CsPrinter.printBitmap(useCtx, combined);
                    }

                    // 4. Footer Section (Text Pool)
                    printer = new CsPrinter();
                    if (footerData != null) {
                        for (int i = 0; i < footerData.size(); i++) {
                            String text = footerData.getString(i);
                            if (text != null && !text.trim().isEmpty()) {
                                printer.addTextToPrint(text, null, 24, false, false, 1);
                            }
                        }
                    }
                    printer.print(useCtx);
                    CsPrinter.printEndLine();

                } catch (Exception e) {
                    Log.e("Mobiprint3plus", "Error in unified printMobiwirePrinter", e);
                }
            }
        }).start();
    }

    /**
     * Renders a single line of text into a Bitmap.
     * Supports Label\tValue split rendering if \t is present.
     */
    private Bitmap renderTextLineToBitmap(String text, int paperWidth, int textSize, boolean isBold) {
        try {
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setAntiAlias(false);
            paint.setColor(Color.BLACK);
            paint.setTextSize(textSize);
            paint.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    isBold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));

            android.graphics.Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            int lineHeight = fm.descent - fm.ascent + 2;

            Bitmap bmp = Bitmap.createBitmap(paperWidth, lineHeight, Bitmap.Config.RGB_565);
            bmp.eraseColor(Color.WHITE);
            Canvas c = new Canvas(bmp);

            if (text.contains("\t")) {
                String[] parts = text.split("\t", 2);
                String labelPart = parts[0];
                String valuePart = parts[1];

                // For RTL (Arabic): Draw Label on the RIGHT and Value on the LEFT
                paint.setTextAlign(android.graphics.Paint.Align.RIGHT);
                c.drawText(labelPart, paperWidth, -fm.ascent + 1, paint);

                paint.setTextAlign(android.graphics.Paint.Align.LEFT);
                c.drawText(valuePart, 0, -fm.ascent + 1, paint);
            } else {
                // Draw Centred (Titles/Dividers)
                paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                c.drawText(text, paperWidth / 2f, -fm.ascent + 1, paint);
            }
            return bmp;
        } catch (Exception e) {
            Log.e("Mobiprint3plus", "renderTextLineToBitmap failed: " + e.getMessage());
            return null;
        }
    }

    private Bitmap processBitmapForThermalPrinter(Bitmap myBitmap, int width) {
        if (myBitmap == null)
            return null;

        try {
            float ratio = (float) myBitmap.getHeight() / (float) myBitmap.getWidth();
            int height = (int) (width * ratio);

            Bitmap scaled = Bitmap.createScaledBitmap(myBitmap, width, height, true);
            Bitmap mono = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            int[] pixels = new int[width * height];
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);

            int[] histogram = new int[256];
            int[] grayPixels = new int[pixels.length];
            int validPixels = 0;

            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int alpha = (pixel >> 24) & 0xff;

                if (alpha < 128) {
                    grayPixels[i] = -1;
                    continue;
                }

                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                grayPixels[i] = gray;
                histogram[gray]++;
                validPixels++;
            }

            int threshold = 128;
            if (validPixels > 0) {
                double sum = 0;
                for (int i = 0; i < 256; i++) {
                    sum += i * histogram[i];
                }

                double sumB = 0;
                int wB = 0;
                int wF = 0;
                double maxVariance = 0;

                for (int t = 0; t < 256; t++) {
                    wB += histogram[t];
                    if (wB == 0)
                        continue;

                    wF = validPixels - wB;
                    if (wF == 0)
                        break;

                    sumB += t * histogram[t];
                    double mB = sumB / wB;
                    double mF = (sum - sumB) / wF;
                    double variance = wB * wF * (mB - mF) * (mB - mF);

                    if (variance > maxVariance) {
                        maxVariance = variance;
                        threshold = t;
                    }
                }
            }

            threshold = (int) (threshold * 0.85);

            int[][] errorBuffer = new int[height][width];

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = y * width + x;

                    if (grayPixels[i] == -1) {
                        pixels[i] = Color.WHITE;
                        continue;
                    }

                    int gray = grayPixels[i] + errorBuffer[y][x];
                    gray = Math.max(0, Math.min(255, gray));

                    int newColor;
                    int error;
                    if (gray < threshold) {
                        newColor = Color.BLACK;
                        error = gray;
                    } else {
                        newColor = Color.WHITE;
                        error = gray - 255;
                    }

                    pixels[i] = newColor;

                    if (x + 1 < width)
                        errorBuffer[y][x + 1] += error * 7 / 16;
                    if (y + 1 < height) {
                        if (x > 0)
                            errorBuffer[y + 1][x - 1] += error * 3 / 16;
                        errorBuffer[y + 1][x] += error * 5 / 16;
                        if (x + 1 < width)
                            errorBuffer[y + 1][x + 1] += error * 1 / 16;
                    }
                }
            }

            mono.setPixels(pixels, 0, width, 0, 0, width, height);
            return mono;
        } catch (Exception e) {
            Log.e("Mobiprint3plus", "Error processing bitmap", e);
        }
        return null;
    }

    private Bitmap centerBitmap(Bitmap original, int paperWidth) {
        if (original == null)
            return null;
        if (original.getWidth() >= paperWidth)
            return original;

        try {
            Bitmap centered = Bitmap.createBitmap(paperWidth, original.getHeight(), Bitmap.Config.RGB_565);
            centered.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(centered);
            float left = (paperWidth - original.getWidth()) / 2f;
            canvas.drawBitmap(original, left, 0, null);
            return centered;
        } catch (Exception e) {
            Log.e("Mobiprint3plus", "Error centering bitmap", e);
        }
        return original;
    }

    private Bitmap downloadAndProcessBitmap(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.setConnectTimeout(10000);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap myBitmap = BitmapFactory.decodeStream(input);
            Bitmap processed = processBitmapForThermalPrinter(myBitmap, 150);
            return centerBitmap(processed, 384);
        } catch (Exception e) {
            Log.e("Mobiprint3plus", "Error downloading bitmap", e);
        }
        return null;
    }

    private Bitmap generateCustomQRCode(String content, int width, int height) {
        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height);
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return centerBitmap(bitmap, 384);
        } catch (WriterException e) {
            Log.e("Mobiprint3plus", "Error generating QR code", e);
        }
        return null;
    }

    private int[] parseIntArray(ReadableArray array) {
        if (array == null)
            return null;
        int[] result = new int[array.size()];
        for (int i = 0; i < array.size(); i++) {
            result[i] = array.getInt(i);
        }
        return result;
    }

    private boolean isBoldLine(int currentIndex, int[] boldLineIndices) {
        if (boldLineIndices == null)
            return false;
        for (int index : boldLineIndices) {
            if (index == currentIndex)
                return true;
        }
        return false;
    }

    @ReactMethod
    public void addTextToPrint(String text, String textTwo, int textSize, boolean isBold, boolean isUnderline,
            int align) {
        printer.addTextToPrint(text, textTwo, textSize, isBold, isUnderline, align);
    }

    @ReactMethod
    public void printImageFromUrl(final String imageUrl, final Callback successCallback, final Callback errorCallback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(imageUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setDoInput(true);
                    connection.setConnectTimeout(10000);
                    connection.connect();
                    InputStream input = connection.getInputStream();
                    Bitmap myBitmap = BitmapFactory.decodeStream(input);

                    if (myBitmap != null) {
                        pendingBitmap = processBitmapForThermalPrinter(myBitmap, 260);
                        successCallback.invoke();
                    } else {
                        errorCallback.invoke("Failed to decode image");
                    }
                } catch (Exception e) {
                    errorCallback.invoke(e.getMessage());
                }
            }
        }).start();
    }

    @ReactMethod
    public void connectPOS() {
        Context context = this.reactContext.getCurrentActivity();
        this.printer = new CsPrinter();
        try {
            new AndroidGoCSApi(context);
        } catch (NullPointerException ex) {
            ex.printStackTrace();
        }
    }

    @ReactMethod
    public void print() {
        Log.d("Mobiprint3plus", "Print command received");
        Context context = this.reactContext.getCurrentActivity();
        Context useCtx = (context != null) ? context : reactContext;

        try {
            if (pendingBitmap != null) {
                Log.d("Mobiprint3plus", "Printing pending bitmap directly...");
                CsPrinter.printBitmap(useCtx, pendingBitmap);
                pendingBitmap = null;
            }
            if (printer != null) {
                Log.d("Mobiprint3plus", "Printing text queue...");
                printer.print(useCtx);
            }
            CsPrinter.printEndLine();
        } catch (Exception e) {
            Log.e("Mobiprint3plus", "Error during print", e);
        }
    }
}