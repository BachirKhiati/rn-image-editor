package com.imageeditor;

import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapRegionDecoder;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.JSApplicationIllegalArgumentException;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.ReactConstants;

/**
 * Native module that provides image cropping functionality.
 */
public class ImageEditorModule extends ReactContextBaseJavaModule {

  protected static final String NAME = "RNCImageEditor";

  private final static String IMAGE_JPEG = "image/jpeg";
  private final static String IMAGE_PNG = "image/png";
  private final static String SCHEME_DATA = "data";
  private final static String SCHEME_CONTENT = "content";
  private final static String SCHEME_FILE = "file";

  private static final List<String> LOCAL_URI_PREFIXES = Arrays.asList(
          ContentResolver.SCHEME_FILE,
          ContentResolver.SCHEME_CONTENT,
          ContentResolver.SCHEME_ANDROID_RESOURCE
  );

  private static final String TEMP_FILE_PREFIX = "ReactNative_cropped_image_";

  /** Compress quality of the output file. */
  private static final int COMPRESS_QUALITY = 90;

  @SuppressLint("InlinedApi") private static final String[] EXIF_ATTRIBUTES = new String[] {
          ExifInterface.TAG_APERTURE,
          ExifInterface.TAG_DATETIME,
          ExifInterface.TAG_DATETIME_DIGITIZED,
          ExifInterface.TAG_EXPOSURE_TIME,
          ExifInterface.TAG_FLASH,
          ExifInterface.TAG_FOCAL_LENGTH,
          ExifInterface.TAG_GPS_ALTITUDE,
          ExifInterface.TAG_GPS_ALTITUDE_REF,
          ExifInterface.TAG_GPS_DATESTAMP,
          ExifInterface.TAG_GPS_LATITUDE,
          ExifInterface.TAG_GPS_LATITUDE_REF,
          ExifInterface.TAG_GPS_LONGITUDE,
          ExifInterface.TAG_GPS_LONGITUDE_REF,
          ExifInterface.TAG_GPS_PROCESSING_METHOD,
          ExifInterface.TAG_GPS_TIMESTAMP,
          ExifInterface.TAG_IMAGE_LENGTH,
          ExifInterface.TAG_IMAGE_WIDTH,
          ExifInterface.TAG_ISO,
          ExifInterface.TAG_MAKE,
          ExifInterface.TAG_MODEL,
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.TAG_SUBSEC_TIME,
          ExifInterface.TAG_SUBSEC_TIME_DIG,
          ExifInterface.TAG_SUBSEC_TIME_ORIG,
          ExifInterface.TAG_WHITE_BALANCE
  };

  public ImageEditorModule(ReactApplicationContext reactContext) {
    super(reactContext);
    new CleanTask(getReactApplicationContext()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public Map<String, Object> getConstants() {
    return Collections.emptyMap();
  }

  @Override
  public void onCatalystInstanceDestroy() {
    new CleanTask(getReactApplicationContext()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  /**
   * Asynchronous task that cleans up cache dirs (internal and, if available, external) of cropped
   * image files. This is run when the catalyst instance is being destroyed (i.e. app is shutting
   * down) and when the module is instantiated, to handle the case where the app crashed.
   */
  private static class CleanTask extends GuardedAsyncTask<Void, Void> {
    private final Context mContext;

    private CleanTask(ReactContext context) {
      super(context);
      mContext = context;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      cleanDirectory(mContext.getCacheDir());
      File externalCacheDir = mContext.getExternalCacheDir();
      if (externalCacheDir != null) {
        cleanDirectory(externalCacheDir);
      }
    }

    private void cleanDirectory(File directory) {
      File[] toDelete = directory.listFiles(
              new FilenameFilter() {
                @Override
                public boolean accept(File dir, String filename) {
                  return filename.startsWith(TEMP_FILE_PREFIX);
                }
              });
      if (toDelete != null) {
        for (File file: toDelete) {
          file.delete();
        }
      }
    }
  }

  /**
   * Crop an image. If all goes well, the promise will be resolved with the file:// URI of
   * the new image as the only argument. This is a temporary file - consider using
   * CameraRollManager.saveImageWithTag to save it in the gallery.
   *
   * @param uri the URI of the image to crop
   * @param options crop parameters specified as {@code {offset: {x, y}, size: {width, height}}}.
   *        Optionally this also contains  {@code {targetSize: {width, height}}}. If this is
   *        specified, the cropped image will be resized to that size.
   *        All units are in pixels (not DPs).
   * @param promise Promise to be resolved when the image has been cropped; the only argument that
   *        is passed to this is the file:// URI of the new image
   */
  @ReactMethod
  public void cropImage(
          String uri,
          ReadableMap options,
          Promise promise) {
    ReadableMap offset = options.hasKey("offset") ? options.getMap("offset") : null;
    ReadableMap size = options.hasKey("size") ? options.getMap("size") : null;
    if (offset == null || size == null ||
            !offset.hasKey("x") || !offset.hasKey("y") ||
            !size.hasKey("width") || !size.hasKey("height")) {
      throw new JSApplicationIllegalArgumentException("Please specify offset and size");
    }
    if (uri == null || uri.isEmpty()) {
      throw new JSApplicationIllegalArgumentException("Please specify a URI");
    }

    CropTask cropTask = new CropTask(
            getReactApplicationContext(),
            uri,
            (int) offset.getDouble("x"),
            (int) offset.getDouble("y"),
            (int) size.getDouble("width"),
            (int) size.getDouble("height"),
            promise);
    if (options.hasKey("displaySize")) {
      ReadableMap targetSize = options.getMap("displaySize");
      cropTask.setTargetSize(
              (int) targetSize.getDouble("width"),
              (int) targetSize.getDouble("height"));
    }
    cropTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }



  @ReactMethod
  public void getSize(String uri, final Promise promise) {
    try {
      Uri u = Uri.parse(uri);
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;

      int height = 0;
      int width = 0;

      if (uri.startsWith("file://")) {
        BitmapFactory.decodeFile(u.getPath(), options);
        int orientation= getOrientation(getReactApplicationContext(), u);
        if(orientation == 90 || orientation == 270){
          height = options.outWidth;
          width = options.outHeight;
        } else {
          height = options.outHeight;
          width = options.outWidth;
        }

      } else {
        URL url = new URL(uri);
        Bitmap bitmap = BitmapFactory.decodeStream((InputStream) url.getContent());
        int orientation= getOrientation(getReactApplicationContext(), u);
        if(orientation == 90 || orientation == 270){
          height = bitmap.getWidth();
          width = bitmap.getHeight();
        } else {
          height = bitmap.getHeight();
          width = bitmap.getWidth();
        }

      }




      WritableMap map = Arguments.createMap();

      map.putInt("height", height);
      map.putInt("width", width);

      promise.resolve(map);
    } catch (Exception e) {
      promise.reject(e);
    }
  }


  @ReactMethod
  public void getBase64(final String uri, final Promise promise) {
    AsyncTask.execute(new Runnable() {
      @Override
      public void run() {
        //TODO your background code
        try {
          Bitmap bmp = BitmapFactory.decodeStream(new java.net.URL(uri).openStream());
          String base64String = ImageUtil.convert(bmp);
          promise.resolve(base64String);
        } catch (Exception e) {
          promise.reject(e);
        }
      }
    });
  }

  @ReactMethod
  public void rotate(String imagePath,int quality, int rotation, String outputPath, final Promise promise) {
    RotateTask rotateTask = new RotateTask(
            getReactApplicationContext(),
            imagePath,
            (int) quality,
            (int) rotation,
            outputPath,
            promise);
    rotateTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

//    try {
//      createResizedImageWithExceptions(imagePath, compressFormat, quality,
//              rotation, outputPath, promise);
//    } catch (IOException e) {
//      promise.resolve(e.getMessage());
//    }
  }

  private static class CropTask extends GuardedAsyncTask<Void, Void> {
    final Context mContext;
    final String mUri;
    final int mX;
    final int mY;
    final int mWidth;
    final int mHeight;
    int mTargetWidth = 0;
    int mTargetHeight = 0;
    final Promise mPromise;


    private CropTask(
            ReactContext context,
            String uri,
            int x,
            int y,
            int width,
            int height,
            Promise promise) {
      super(context);
      if (x < 0 || y < 0 || width <= 0 || height <= 0) {
        throw new JSApplicationIllegalArgumentException(String.format(
                "Invalid crop rectangle: [%d, %d, %d, %d]", x, y, width, height));
      }
      mContext = context;
      mUri = uri;
      mX = x;
      mY = y;
      mWidth = width;
      mHeight = height;
      mPromise = promise;
    }

    public void setTargetSize(int width, int height) {
      if (width <= 0 || height <= 0) {
        throw new JSApplicationIllegalArgumentException(String.format(
                "Invalid target size: [%d, %d]", width, height));
      }
      mTargetWidth = width;
      mTargetHeight = height;
    }

    private InputStream openBitmapInputStream() throws IOException {
      InputStream stream;
      if (isLocalUri(mUri)) {
        stream = mContext.getContentResolver().openInputStream(Uri.parse(mUri));
      } else {
        URLConnection connection = new URL(mUri).openConnection();
        stream = connection.getInputStream();
      }
      if (stream == null) {
        throw new IOException("Cannot open bitmap: " + mUri);
      }
      return stream;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      try {
        BitmapFactory.Options outOptions = new BitmapFactory.Options();

        // If we're downscaling, we can decode the bitmap more efficiently, using less memory
        boolean hasTargetSize = (mTargetWidth > 0) && (mTargetHeight > 0);

        Bitmap cropped;
        if (hasTargetSize) {
          cropped = cropAndResize(mTargetWidth, mTargetHeight, outOptions);
        } else {
          cropped = crop(outOptions);
        }

        String mimeType = outOptions.outMimeType;
        if (mimeType == null || mimeType.isEmpty()) {
          throw new IOException("Could not determine MIME type");
        }

        File tempFile = createTempFile(mContext, mimeType);
        writeCompressedBitmapToFile(cropped, mimeType, tempFile);

        if (mimeType.equals("image/jpeg")) {
//          copyExif(mContext, Uri.parse(mUri), tempFile);
        }

        mPromise.resolve(Uri.fromFile(tempFile).toString());
      } catch (Exception e) {
        mPromise.reject(e);
      }
    }

    /**
     * Reads and crops the bitmap.
     * @param outOptions Bitmap options, useful to determine {@code outMimeType}.
     */
    private Bitmap crop(BitmapFactory.Options outOptions) throws IOException {
      InputStream inputStream = openBitmapInputStream();
      // Effeciently crops image without loading full resolution into memory
      // https://developer.android.com/reference/android/graphics/BitmapRegionDecoder.html
      BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(inputStream, false);
      try {
        Rect rect = new Rect(mX, mY, mX + mWidth, mY + mHeight);
        return decoder.decodeRegion(rect, outOptions);
      } finally {
        if (inputStream != null) {
          inputStream.close();
        }
        decoder.recycle();
      }
    }

    /**
     * Crop the rectangle given by {@code mX, mY, mWidth, mHeight} within the source bitmap
     * and scale the result to {@code targetWidth, targetHeight}.
     * @param outOptions Bitmap options, useful to determine {@code outMimeType}.
     */
    private Bitmap cropAndResize(
            int targetWidth,
            int targetHeight,
            BitmapFactory.Options outOptions)
            throws IOException {
      Assertions.assertNotNull(outOptions);

      // Loading large bitmaps efficiently:
      // http://developer.android.com/training/displaying-bitmaps/load-bitmap.html

      // This uses scaling mode COVER

      // Where would the crop rect end up within the scaled bitmap?
      float newWidth, newHeight, newX, newY, scale;
      float cropRectRatio = mWidth / (float) mHeight;
      float targetRatio = targetWidth / (float) targetHeight;
      if (cropRectRatio > targetRatio) {
        // e.g. source is landscape, target is portrait
        newWidth = mHeight * targetRatio;
        newHeight = mHeight;
        newX = mX + (mWidth - newWidth) / 2;
        newY = mY;
        scale = targetHeight / (float) mHeight;
      } else {
        // e.g. source is landscape, target is portrait
        newWidth = mWidth;
        newHeight = mWidth / targetRatio;
        newX = mX;
        newY = mY + (mHeight - newHeight) / 2;
        scale = targetWidth / (float) mWidth;
      }

      // Decode the bitmap. We have to open the stream again, like in the example linked above.
      // Is there a way to just continue reading from the stream?
      outOptions.inSampleSize = getDecodeSampleSize(mWidth, mHeight, targetWidth, targetHeight);
      InputStream inputStream = openBitmapInputStream();

      Bitmap bitmap;
      try {
        // This can use significantly less memory than decoding the full-resolution bitmap
        bitmap = BitmapFactory.decodeStream(inputStream, null, outOptions);

        int orientation = getOrientation(mContext, Uri.parse(mUri));
        if(orientation != 0) {
          bitmap = rotateImage(bitmap, orientation);
        }

        if (bitmap == null) {
          throw new IOException("Cannot decode bitmap: " + mUri);
        }
      } finally {
        if (inputStream != null) {
          inputStream.close();
        }
      }

      int cropX = (int) Math.floor(newX / (float) outOptions.inSampleSize);
      int cropY = (int) Math.floor(newY / (float) outOptions.inSampleSize);
      int cropWidth = (int) Math.floor(newWidth / (float) outOptions.inSampleSize);
      int cropHeight = (int) Math.floor(newHeight / (float) outOptions.inSampleSize);
      float cropScale = scale * outOptions.inSampleSize;

      Matrix scaleMatrix = new Matrix();
      scaleMatrix.setScale(cropScale, cropScale);
      boolean filter = true;

      return Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight, scaleMatrix, filter);
    }
  }


  private static class RotateTask extends GuardedAsyncTask<Void, Void> {
    final Context mContext;
    final String mUri;
    final Bitmap.CompressFormat mCompressFormat;
    final int mQuality;
    int mRotation;
    final String mOutputPath;
    final Promise mPromise;

    private RotateTask(
            ReactContext context,
            String uri,
            int quality,
            int rotation,
            String outputPath,
            Promise promise) {
      super(context);
      if (quality < 0 || uri.isEmpty()) {
        promise.reject("error","Content cannot be null");
      }
      mContext = context;
      mUri = uri;
      mCompressFormat = Bitmap.CompressFormat.JPEG;
      mQuality = quality;
      mRotation = rotation;
      mOutputPath = outputPath;
      mPromise = promise;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      try {
        Uri imageUri = Uri.parse(mUri);
        Bitmap sourceImage = null;
        String imageUriScheme = imageUri.getScheme();
        if (imageUriScheme == null || imageUriScheme.equalsIgnoreCase(SCHEME_FILE) || imageUriScheme.equalsIgnoreCase(SCHEME_CONTENT)) {
          sourceImage = loadBitmapFromFile(mContext, imageUri);
        } else if (imageUriScheme.equalsIgnoreCase(SCHEME_DATA)) {
          sourceImage = loadBitmapFromBase64(imageUri);
        }

        if (sourceImage == null) {
          mPromise.reject("error", "Unable to load source image from path" );
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        InputStream in = mContext.getContentResolver().openInputStream(
                imageUri);
        BitmapFactory.decodeStream(in, null, options);

        int orientation = getOrientation(mContext, imageUri);
        mRotation = orientation + mRotation;
        if(mRotation != 0) {
          sourceImage = rotateImage(sourceImage, mRotation);
        }

        // Save the resulting image
        File path = mContext.getCacheDir();
        String lastPathSegment;
        if (mOutputPath != null) {
          Uri uri = Uri.parse(mOutputPath);
          lastPathSegment = uri.getLastPathSegment();
        }else{
          lastPathSegment = Long.toString(new Date().getTime());
        }
        File newFile = saveImage(sourceImage, path,
                lastPathSegment, mCompressFormat, mQuality);
        // Clean up remaining image
        sourceImage.recycle();

        WritableMap response = Arguments.createMap();
        response.putString("path", newFile.getAbsolutePath());
        response.putString("uri", Uri.fromFile(newFile).toString());
        response.putString("name", newFile.getName());
        response.putDouble("size", newFile.length());
        mPromise.resolve(response);

      } catch (Exception e) {
        mPromise.reject(e);

      }

      // Utils


    }

  }


  /**
   * Loads the bitmap resource from the file specified in imagePath.
   */
  private static Bitmap loadBitmapFromFile(Context context, Uri imageUri) throws IOException  {
    // Decode the image bounds to find the size of the source image.
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    loadBitmap(context, imageUri, options);

    // Set a sample size according to the image size to lower memory usage.
//    options.inSampleSize = calculateInSampleSize(options, newWidth, newHeight);
    options.inJustDecodeBounds = false;
    System.out.println(options.inSampleSize);
    return loadBitmap(context, imageUri, options);

  }




  private static void copyExif(Context context, Uri oldImage, File newFile) throws IOException {
    File oldFile = getFileFromUri(context, oldImage);
    if (oldFile == null) {
      FLog.w(ReactConstants.TAG, "Couldn't get real path for uri: " + oldImage);
      return;
    }

    ExifInterface oldExif = new ExifInterface(oldFile.getAbsolutePath());
    ExifInterface newExif = new ExifInterface(newFile.getAbsolutePath());
    for (String attribute : EXIF_ATTRIBUTES) {
      String value = oldExif.getAttribute(attribute);
      if (value != null) {
        newExif.setAttribute(attribute, value);
      }
    }
    newExif.saveAttributes();
  }

  private static @Nullable File getFileFromUri(Context context, Uri uri) {
    if (uri.getScheme().equals("file")) {
      return new File(uri.getPath());
    } else if (uri.getScheme().equals("content")) {
      Cursor cursor = context.getContentResolver()
              .query(uri, new String[] { MediaStore.MediaColumns.DATA }, null, null, null);
      if (cursor != null) {
        try {
          if (cursor.moveToFirst()) {
            String path = cursor.getString(0);
            if (!TextUtils.isEmpty(path)) {
              return new File(path);
            }
          }
        } finally {
          cursor.close();
        }
      }
    }

    return null;
  }

  private static boolean isLocalUri(String uri) {
    for (String localPrefix : LOCAL_URI_PREFIXES) {
      if (uri.startsWith(localPrefix)) {
        return true;
      }
    }
    return false;
  }

  private static String getFileExtensionForType(@Nullable String mimeType) {
    if ("image/png".equals(mimeType)) {
      return ".png";
    }
    if ("image/webp".equals(mimeType)) {
      return ".webp";
    }
    return ".jpg";
  }

  private static Bitmap.CompressFormat getCompressFormatForType(String type) {
    if ("image/png".equals(type)) {
      return Bitmap.CompressFormat.PNG;
    }
    if ("image/webp".equals(type)) {
      return Bitmap.CompressFormat.WEBP;
    }
    return Bitmap.CompressFormat.JPEG;
  }

  private static void writeCompressedBitmapToFile(Bitmap cropped, String mimeType, File tempFile)
          throws IOException {
    OutputStream out = new FileOutputStream(tempFile);
    try {
      cropped.compress(getCompressFormatForType(mimeType), COMPRESS_QUALITY, out);
    } finally {
      if (out != null) {
        out.close();
      }
    }
  }

  /**
   * Create a temporary file in the cache directory on either internal or external storage,
   * whichever is available and has more free space.
   *
   * @param mimeType the MIME type of the file to create (image/*)
   */
  private static File createTempFile(Context context, @Nullable String mimeType)
          throws IOException {
    File externalCacheDir = context.getExternalCacheDir();
    File internalCacheDir = context.getCacheDir();
    File cacheDir;
    if (externalCacheDir == null && internalCacheDir == null) {
      throw new IOException("No cache directory available");
    }
    if (externalCacheDir == null) {
      cacheDir = internalCacheDir;
    }
    else if (internalCacheDir == null) {
      cacheDir = externalCacheDir;
    } else {
      cacheDir = externalCacheDir.getFreeSpace() > internalCacheDir.getFreeSpace() ?
              externalCacheDir : internalCacheDir;
    }
    return File.createTempFile(TEMP_FILE_PREFIX, getFileExtensionForType(mimeType), cacheDir);
  }

  /**
   * When scaling down the bitmap, decode only every n-th pixel in each dimension.
   * Calculate the largest {@code inSampleSize} value that is a power of 2 and keeps both
   * {@code width, height} larger or equal to {@code targetWidth, targetHeight}.
   * This can significantly reduce memory usage.
   */
  private static int getDecodeSampleSize(int width, int height, int targetWidth, int targetHeight) {
    int inSampleSize = 1;
    if (height > targetHeight || width > targetWidth) {
      int halfHeight = height / 2;
      int halfWidth = width / 2;
      while ((halfWidth / inSampleSize) >= targetWidth
              && (halfHeight / inSampleSize) >= targetHeight) {
        inSampleSize *= 2;
      }
    }
    return inSampleSize;
  }

  public static Bitmap rotateImage(Bitmap source, float angle)
  {
    Bitmap retVal;

    Matrix matrix = new Matrix();
    matrix.postRotate(angle);
    retVal = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    return retVal;
  }

  /**
   * Get orientation by reading Image metadata
   */
  public static int getOrientation(Context context, Uri uri) {
    try {
      File file = getFileFromUri(context, uri);
      if (file.exists()) {
        ExifInterface ei = new ExifInterface(file.getAbsolutePath());
        return getOrientation(ei);
      }
    } catch (Exception ignored) { }

    return 0;
  }

  /**
   * Convert metadata to degrees
   */
  public static int getOrientation(ExifInterface exif) {
    int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
    switch (orientation) {
      case ExifInterface.ORIENTATION_ROTATE_90:
        return 90;
      case ExifInterface.ORIENTATION_ROTATE_180:
        return 180;
      case ExifInterface.ORIENTATION_ROTATE_270:
        return 270;
      default:
        return 0;
    }
  }



  /**
   * Loads the bitmap resource from a base64 encoded jpg or png.
   * Format is as such:
   * png: 'data:image/png;base64,iVBORw0KGgoAA...'
   * jpg: 'data:image/jpeg;base64,/9j/4AAQSkZJ...'
   */
  private static Bitmap loadBitmapFromBase64(Uri imageUri) {
    Bitmap sourceImage = null;
    String imagePath = imageUri.getSchemeSpecificPart();
    int commaLocation = imagePath.indexOf(',');
    if (commaLocation != -1) {
      final String mimeType = imagePath.substring(0, commaLocation).replace('\\','/').toLowerCase();
      final boolean isJpeg = mimeType.startsWith(IMAGE_JPEG);
      final boolean isPng = !isJpeg && mimeType.startsWith(IMAGE_PNG);

      if (isJpeg || isPng) {
        // base64 image. Convert to a bitmap.
        final String encodedImage = imagePath.substring(commaLocation + 1);
        final byte[] decodedString = Base64.decode(encodedImage, Base64.DEFAULT);
        sourceImage = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
      }
    }

    return sourceImage;
  }

  /**
   * Compute the inSampleSize value to use to load a bitmap.
   * Adapted from https://developer.android.com/training/displaying-bitmaps/load-bitmap.html
   */
  private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
    final int height = options.outHeight;
    final int width = options.outWidth;

    int inSampleSize = 1;

    if (height > reqHeight || width > reqWidth) {
      final int halfHeight = height / 2;
      final int halfWidth = width / 2;

      // Calculate the largest inSampleSize value that is a power of 2 and keeps both
      // height and width larger than the requested height and width.
      while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
        inSampleSize *= 2;
      }
    }

    return inSampleSize;
  }

  /**
   * Load a bitmap either from a real file or using the {@link ContentResolver} of the current
   * {@link Context} (to read gallery images for example).
   *
   * Note that, when options.inJustDecodeBounds = true, we actually expect sourceImage to remain
   * as null (see https://developer.android.com/training/displaying-bitmaps/load-bitmap.html), so
   * getting null sourceImage at the completion of this method is not always worthy of an error.
   */
  private static Bitmap loadBitmap(Context context, Uri imageUri, BitmapFactory.Options options) throws IOException {
    Bitmap sourceImage = null;
    String imageUriScheme = imageUri.getScheme();
    if (imageUriScheme == null || !imageUriScheme.equalsIgnoreCase(SCHEME_CONTENT)) {
      try {
        sourceImage = BitmapFactory.decodeFile(imageUri.getPath(), options);
      } catch (Exception e) {
        e.printStackTrace();
        throw new IOException("Error decoding image file");
      }
    } else {
      ContentResolver cr = context.getContentResolver();
      InputStream input = cr.openInputStream(imageUri);
      if (input != null) {
        sourceImage = BitmapFactory.decodeStream(input, null, options);
        input.close();
      }
    }
    return sourceImage;
  }

  /**
   * Save the given bitmap in a directory. Extension is automatically generated using the bitmap format.
   */
  private static File saveImage(Bitmap bitmap, File saveDirectory, String fileName,
                                Bitmap.CompressFormat compressFormat, int quality)
          throws IOException {
    if (bitmap == null) {
      throw new IOException("The bitmap couldn't be resized");
    }

    File newFile = new File(saveDirectory, fileName + "." + compressFormat.name());
    if(!newFile.createNewFile()) {
      throw new IOException("The file already exists");
    }

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    bitmap.compress(compressFormat, quality, outputStream);
    byte[] bitmapData = outputStream.toByteArray();

    outputStream.flush();
    outputStream.close();

    FileOutputStream fos = new FileOutputStream(newFile);
    fos.write(bitmapData);
    fos.flush();
    fos.close();

    return newFile;
  }
}