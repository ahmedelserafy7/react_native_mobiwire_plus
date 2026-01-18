// Mobiprint3plusModule.java

package com.mm.treka.mobiprint3plus;

import android.content.Context;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.mobiwire.CSAndroidGoLib.AndroidGoCSApi;
import com.mobiwire.CSAndroidGoLib.CsPrinter;
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
    public void addTextToPrint(String text, String textTwo, int textSize, boolean isBold, boolean isUnderline, int align) {
        printer.addTextToPrint(text,textTwo,textSize,isBold,isUnderline,align);
    }

    @ReactMethod
    public void printImageFromUrl(final String imageUrl, final Callback successCallback, final Callback errorCallback) {
        Log.d("Mobiprint3plus", "Starting image download: " + imageUrl);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(imageUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setDoInput(true);
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(10000);
                    connection.connect();
                    InputStream input = connection.getInputStream();
                    Bitmap myBitmap = BitmapFactory.decodeStream(input);
                    
                    if (myBitmap != null) {
                        Log.d("Mobiprint3plus", "Image decoded successfully: " + myBitmap.getWidth() + "x" + myBitmap.getHeight());
                        
                        // Creative Solution: Manual Monochrome Scaling & Thresholding
                        // Thermal printers work best with strictly black and white pixels.
                        int width = 384; // Standard width
                        float ratio = (float) myBitmap.getHeight() / (float) myBitmap.getWidth();
                        int height = (int) (width * ratio);
                        
                        Bitmap scaled = Bitmap.createScaledBitmap(myBitmap, width, height, true);
                        Bitmap mono = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                        
                        int[] pixels = new int[width * height];
                        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
                        
                        for (int i = 0; i < pixels.length; i++) {
                            int pixel = pixels[i];
                            // Extract RGB
                            int r = (pixel >> 16) & 0xff;
                            int g = (pixel >> 8) & 0xff;
                            int b = pixel & 0xff;
                            // Calculate luminance
                            int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                            // Threshold: pixels darker than 128 become black, others white
                            pixels[i] = (gray < 128) ? Color.BLACK : Color.WHITE;
                        }
                        
                        mono.setPixels(pixels, 0, width, 0, 0, width, height);
                        Log.d("Mobiprint3plus", "Monochrome conversion complete");

                        pendingBitmap = mono;
                        Log.d("Mobiprint3plus", "Bitmap stored in pendingBitmap");
                        successCallback.invoke();
                    } else {
                        errorCallback.invoke("Failed to decode image from URL");
                    }
                } catch (Exception e) {
                    Log.e("Mobiprint3plus", "Error in printImageFromUrl", e);
                    errorCallback.invoke(e.getMessage());
                }
            }
        }).start();
    }

    @ReactMethod
    public void printLine() {
        printer.addTextToPrint("-------------------------------------------------------", null, 25, true, false, 1);
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
            // 1. If we have a pending image, use the DIRECT static method to force print it
            if (pendingBitmap != null) {
                Log.d("Mobiprint3plus", "Printing pending bitmap directly...");
                CsPrinter.printBitmap(useCtx, pendingBitmap);
                pendingBitmap = null;
            }

            // 2. Print the rest of the queue (text, lines, etc.)
            if (printer != null) {
                Log.d("Mobiprint3plus", "Printing text queue...");
                printer.print(useCtx);
            }

            // 3. Force paper feed so the image at the bottom is visible
            Log.d("Mobiprint3plus", "Feeding paper...");
            for (int i = 0; i < 4; i++) {
                CsPrinter.printEndLine();
            }
        } catch (Exception e) {
            Log.e("Mobiprint3plus", "Error during print", e);
        }
    }

    @ReactMethod
    public void feedPaper(int lines) {
        for (int i = 0; i < lines; i++) {
            CsPrinter.printEndLine();
        }
    }

}
