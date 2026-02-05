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
                        
                        // IMPROVED: Optimized for thermal printer clarity
                        // Reduced width for better compatibility
                        int width = 320; // Reduced from 360 for safer margins
                        float ratio = (float) myBitmap.getHeight() / (float) myBitmap.getWidth();
                        int height = (int) (width * ratio);
                        
                        // Use high-quality filtering for better scaling
                        Bitmap scaled = Bitmap.createScaledBitmap(myBitmap, width, height, true);
                        Bitmap mono = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                        
                        int[] pixels = new int[width * height];
                        scaled.getPixels(pixels, 0, width, 0, 0, width, height);
                        
                        // IMPROVED: Better threshold calculation using Otsu's method approximation
                        // Calculate histogram
                        int[] histogram = new int[256];
                        int[] grayPixels = new int[pixels.length];
                        int validPixels = 0;
                        
                        for (int i = 0; i < pixels.length; i++) {
                            int pixel = pixels[i];
                            int alpha = (pixel >> 24) & 0xff;
                            
                            if (alpha < 128) {
                                grayPixels[i] = -1; // Mark as transparent
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
                        
                        // Calculate optimal threshold using Otsu's method
                        int threshold = 128; // Default
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
                                if (wB == 0) continue;
                                
                                wF = validPixels - wB;
                                if (wF == 0) break;
                                
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
                        
                        // Adjust threshold for better contrast on thermal printers
                        // Thermal printers tend to print lighter, so bias towards more black
                        threshold = (int) (threshold * 0.85); // Make it more sensitive to darker areas
                        
                        Log.d("Mobiprint3plus", "Calculated optimal threshold: " + threshold);
                        
                        // Apply threshold with error diffusion dithering for better quality
                        int[][] errorBuffer = new int[height][width];
                        
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                int i = y * width + x;
                                
                                if (grayPixels[i] == -1) {
                                    // Transparent pixel -> white (paper color)
                                    pixels[i] = Color.WHITE;
                                    continue;
                                }
                                
                                // Add accumulated error
                                int gray = grayPixels[i] + errorBuffer[y][x];
                                gray = Math.max(0, Math.min(255, gray));
                                
                                // Apply threshold
                                int newColor;
                                int error;
                                if (gray < threshold) {
                                    newColor = Color.BLACK;
                                    error = gray; // Error is how much we darkened
                                } else {
                                    newColor = Color.WHITE;
                                    error = gray - 255; // Error is how much we lightened
                                }
                                
                                pixels[i] = newColor;
                                
                                // Floyd-Steinberg error diffusion
                                // Distribute error to neighboring pixels
                                if (x + 1 < width) {
                                    errorBuffer[y][x + 1] += error * 7 / 16;
                                }
                                if (y + 1 < height) {
                                    if (x > 0) {
                                        errorBuffer[y + 1][x - 1] += error * 3 / 16;
                                    }
                                    errorBuffer[y + 1][x] += error * 5 / 16;
                                    if (x + 1 < width) {
                                        errorBuffer[y + 1][x + 1] += error * 1 / 16;
                                    }
                                }
                            }
                        }
                        
                        mono.setPixels(pixels, 0, width, 0, 0, width, height);
                        Log.d("Mobiprint3plus", "Advanced monochrome conversion with dithering complete");

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

            // 3. Minimal feed (1 line) to ensure content clears the cutter
            Log.d("Mobiprint3plus", "Feeding paper...");
            CsPrinter.printEndLine();
            
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
