/*
 * Copyright (C) The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.prapon.eyeblinkdetection.vision;

import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.util.Log;

import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.android.gms.vision.face.Landmark;

import java.sql.Types;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import me.prapon.eyeblinkdetection.CaptureTypes;
import me.prapon.eyeblinkdetection.ClickListener;

/**
 * Tracks the eye positions and state over time, managing an underlying graphic which renders googly
 * eyes over the source video.<p>
 * <p>
 * To improve eye tracking performance, it also helps to keep track of the previous landmark
 * proportions relative to the detected face and to interpolate landmark positions for future
 * updates if the landmarks are missing.  This helps to compensate for intermediate frames where the
 * face was detected but one or both of the eyes were not detected.  Missing landmarks can happen
 * during quick movements due to camera image blurring.
 */
public class FaceTracker extends Tracker<Face> {
    private static final float EYE_CLOSED_THRESHOLD = 0.4f;
    int blinkCount = 0;
    ClickListener clickListener;
    /**
     * Updates the positions and state of eyes to the underlying graphic, according to the most
     * recent face detection results.  The graphic will render the eyes and simulate the motion of
     * the iris based upon these changes over time.
     */

    long lastBlinkStored;
    private GraphicOverlay mOverlay;
    private EyesGraphics mEyesGraphics;
    private EyesGraphics mEarGraphics;
    // Record the previously seen proportions of the landmark locations relative to the bounding box
    // of the face.  These proportions can be used to approximate where the landmarks are within the
    // face bounding box if the eye landmark is missing in a future update.
    @SuppressLint("UseSparseArrays")
    private Map<Integer, PointF> mPreviousProportions = new HashMap<>();


    //==============================================================================================
    // Methods
    //==============================================================================================
    // Similarly, keep track of the previous eye open state so that it can be reused for
    // intermediate frames which lack eye landmarks and corresponding eye state.
    private boolean mPreviousIsLeftOpen = true;
    private boolean mPreviousIsRightOpen = true;

    long lastLeftCapture = 0;
    long lastRightCapture = 0;
    public FaceTracker(GraphicOverlay overlay, ClickListener listener) {
        mOverlay = overlay;
        clickListener = listener;
        long timeInMil = Calendar.getInstance().getTimeInMillis();

         lastLeftCapture = timeInMil;
         lastRightCapture = timeInMil;
    }

    /**
     * Resets the underlying googly eyes graphic and associated physics state.
     */
    @Override
    public void onNewItem(int id, Face face) {
        mEyesGraphics = new EyesGraphics(mOverlay);
        mEarGraphics = new EyesGraphics(mOverlay);
    }


    @Override
    public void onUpdate(FaceDetector.Detections<Face> detectionResults, Face face) {
        mOverlay.add(mEyesGraphics);
        mOverlay.add(mEarGraphics);
        long timeInMil = Calendar.getInstance().getTimeInMillis();

        updatePreviousProportions(face);

        Log.d("FaceRotation", face.getEulerY()+"" );



        if(face.getEulerY()<-40 && face.getEulerZ()>-20 && face.getEulerZ()<20&& timeInMil - lastLeftCapture > 2000){
            lastLeftCapture = timeInMil;
            clickListener.onClick(blinkCount, CaptureTypes.LEFT_FACE);
        } else if (face.getEulerY()>40&& face.getEulerZ()>-20 && face.getEulerZ()<20 && timeInMil - lastRightCapture > 2000) {
            lastRightCapture = timeInMil;
            clickListener.onClick(blinkCount, CaptureTypes.RIGHT_FACE);
        }

        PointF leftPosition = getLandmarkPosition(face, Landmark.LEFT_EYE);
        PointF rightPosition = getLandmarkPosition(face, Landmark.RIGHT_EYE);
        PointF RIGHT_EAR = getLandmarkPosition(face, Landmark.RIGHT_EAR);
        PointF LEFT_EAR = getLandmarkPosition(face, Landmark.LEFT_EAR);
//        PointF LEFT_EAR = getLandmarkPosition(face, Landmark.LEFT_EAR);

        float leftOpenScore = face.getIsLeftEyeOpenProbability();
        boolean isLeftOpen;
        if (leftOpenScore == Face.UNCOMPUTED_PROBABILITY) {
            isLeftOpen = mPreviousIsLeftOpen;
        } else {
            isLeftOpen = (leftOpenScore > EYE_CLOSED_THRESHOLD);
            mPreviousIsLeftOpen = isLeftOpen;
        }

        float rightOpenScore = face.getIsRightEyeOpenProbability();
        boolean isRightOpen;
        if (rightOpenScore == Face.UNCOMPUTED_PROBABILITY) {
            isRightOpen = mPreviousIsRightOpen;
        } else {
            isRightOpen = (rightOpenScore > EYE_CLOSED_THRESHOLD);
            mPreviousIsRightOpen = isRightOpen;
        }



        if ((!mPreviousIsLeftOpen || !mPreviousIsRightOpen) && timeInMil - lastBlinkStored > 2000) {

            lastBlinkStored = timeInMil;
            blinkCount++;
            clickListener.onClick(blinkCount, CaptureTypes.EYE);
        }

//        Log.d("TAG", "updateEyes: mRightOpen: " + mPreviousIsRightOpen + " mLeftOpen: " + mPreviousIsLeftOpen);

//        mEyesGraphics.updateEyes(leftPosition, isLeftOpen, rightPosition, isRightOpen);
//        mEarGraphics.updateEyes(LEFT_EAR, isLeftOpen, RIGHT_EAR, isRightOpen);
    }

    /**
     * Hide the graphic when the corresponding face was not detected.  This can happen for
     * intermediate frames temporarily (e.g., if the face was momentarily blocked from
     * view).
     */
    @Override
    public void onMissing(FaceDetector.Detections<Face> detectionResults) {
        mOverlay.remove(mEyesGraphics);
        mOverlay.remove(mEarGraphics);
    }

    /**
     * Called when the face is assumed to be gone for good. Remove the googly eyes graphic from
     * the overlay.
     */
    @Override
    public void onDone() {
        mOverlay.remove(mEyesGraphics);
        mOverlay.remove(mEarGraphics);
    }

    //==============================================================================================
    // Private
    //==============================================================================================

    private void updatePreviousProportions(Face face) {
        for (Landmark landmark : face.getLandmarks()) {
            PointF position = landmark.getPosition();
            float xProp = (position.x - face.getPosition().x) / face.getWidth();
            float yProp = (position.y - face.getPosition().y) / face.getHeight();
            mPreviousProportions.put(landmark.getType(), new PointF(xProp, yProp));
        }
    }

    /**
     * Finds a specific landmark position, or approximates the position based on past observations
     * if it is not present.
     */
    private PointF getLandmarkPosition(Face face, int landmarkId) {
        for (Landmark landmark : face.getLandmarks()) {
            if (landmark.getType() == landmarkId) {
                return landmark.getPosition();
            }
        }

        PointF prop = mPreviousProportions.get(landmarkId);
        if (prop == null) {
            return null;
        }

        float x = face.getPosition().x + (prop.x * face.getWidth());
        float y = face.getPosition().y + (prop.y * face.getHeight());
        return new PointF(x, y);
    }
}