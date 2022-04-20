/*
 * Copyright 2021 Google LLC
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

package hct;

import utils.ColorUtils;
import utils.MathUtils;

/**
 * A color system built using CAM16 hue and chroma, and L* from L*a*b*.
 *
 * <p>Using L* creates a link between the color system, contrast, and thus accessibility. Contrast
 * ratio depends on relative luminance, or Y in the XYZ color space. L*, or perceptual luminance can
 * be calculated from Y.
 *
 * <p>Unlike Y, L* is linear to human perception, allowing trivial creation of accurate color tones.
 *
 * <p>Unlike contrast ratio, measuring contrast in L* is linear, and simple to calculate. A
 * difference of 40 in HCT tone guarantees a contrast ratio >= 3.0, and a difference of 50
 * guarantees a contrast ratio >= 4.5.
 */

/**
 * HCT, hue, chroma, and tone. A color system that provides a perceptually accurate color
 * measurement system that can also accurately render what colors will appear as in different
 * lighting environments.
 */
public final class Hct {
  private double hue;
  private double chroma;
  private double tone;

  /**
   * Create an HCT color from hue, chroma, and tone.
   *
   * @param hue 0 <= hue < 360; invalid values are corrected.
   * @param chroma 0 <= chroma < ?; Informally, colorfulness. The color returned may be lower than
   *     the requested chroma. Chroma has a different maximum for any given hue and tone.
   * @param tone 0 <= tone <= 100; invalid values are corrected.
   * @return HCT representation of a color in default viewing conditions.
   */
  public static Hct from(double hue, double chroma, double tone) {
    return new Hct(hue, chroma, tone);
  }

  /**
   * Create an HCT color from a color.
   *
   * @param argb ARGB representation of a color.
   * @return HCT representation of a color in default viewing conditions
   */
  public static Hct fromInt(int argb) {
    Cam16 cam = Cam16.fromInt(argb);
    return new Hct(cam.getHue(), cam.getChroma(), ColorUtils.lstarFromArgb(argb));
  }

  private Hct(double hue, double chroma, double tone) {
    setInternalState(gamutMap(hue, chroma, tone));
  }

  public double getHue() {
    return hue;
  }

  public double getChroma() {
    return chroma;
  }

  public double getTone() {
    return tone;
  }

  public int toInt() {
    return gamutMap(hue, chroma, tone);
  }

  /**
   * Set the hue of this color. Chroma may decrease because chroma has a different maximum for any
   * given hue and tone.
   *
   * @param newHue 0 <= newHue < 360; invalid values are corrected.
   */
  public void setHue(double newHue) {
    setInternalState(gamutMap(MathUtils.sanitizeDegreesDouble(newHue), chroma, tone));
  }

  /**
   * Set the chroma of this color. Chroma may decrease because chroma has a different maximum for
   * any given hue and tone.
   *
   * @param newChroma 0 <= newChroma < ?
   */
  public void setChroma(double newChroma) {
    setInternalState(gamutMap(hue, newChroma, tone));
  }

  /**
   * Set the tone of this color. Chroma may decrease because chroma has a different maximum for any
   * given hue and tone.
   *
   * @param newTone 0 <= newTone <= 100; invalid valids are corrected.
   */
  public void setTone(double newTone) {
    setInternalState(gamutMap(hue, chroma, newTone));
  }

  private void setInternalState(int argb) {
    Cam16 cam = Cam16.fromInt(argb);
    double tone = ColorUtils.lstarFromArgb(argb);
    hue = cam.getHue();
    chroma = cam.getChroma();
    this.tone = tone;
  }

  /**
   * When the delta between the floor & ceiling of a binary search for maximum chroma at a hue and
   * tone is less than this, the binary search terminates.
   */
  private static final double CHROMA_SEARCH_ENDPOINT = 0.4;

  /** The maximum color distance, in CAM16-UCS, between a requested color and the color returned. */
  private static final double DE_MAX = 1.0;

  /** The maximum difference between the requested L* and the L* returned. */
  private static final double DL_MAX = 0.2;

  /**
   * The minimum color distance, in CAM16-UCS, between a requested color and an 'exact' match. This
   * allows the binary search during gamut mapping to terminate much earlier when the error is
   * infinitesimal.
   */
  private static final double DE_MAX_ERROR = 0.000000001;

  /**
   * When the delta between the floor & ceiling of a binary search for J, lightness in CAM16, is
   * less than this, the binary search terminates.
   */
  private static final double LIGHTNESS_SEARCH_ENDPOINT = 0.01;

  /**
   * @param hue a number, in degrees, representing ex. red, orange, yellow, etc. Ranges from 0 <=
   *     hue < 360.
   * @param chroma Informally, colorfulness. Ranges from 0 to roughly 150. Like all perceptually
   *     accurate color systems, chroma has a different maximum for any given hue and tone, so the
   *     color returned may be lower than the requested chroma.
   * @param tone Lightness. Ranges from 0 to 100.
   * @return ARGB representation of a color in default viewing conditions
   */
  private static int gamutMap(double hue, double chroma, double tone) {
    return gamutMapInViewingConditions(hue, chroma, tone, ViewingConditions.DEFAULT);
  }

  /**
   * @param hue CAM16 hue.
   * @param chroma CAM16 chroma.
   * @param tone L*a*b* lightness.
   * @param viewingConditions Information about the environment where the color was observed.
   */
  static int gamutMapInViewingConditions(
      double hue, double chroma, double tone, ViewingConditions viewingConditions) {

    if (chroma < 1.0 || Math.round(tone) <= 0.0 || Math.round(tone) >= 100.0) {
      return ColorUtils.argbFromLstar(tone);
    }

    hue = MathUtils.sanitizeDegreesDouble(hue);

    double high = chroma;
    double mid = chroma;
    double low = 0.0;
    boolean isFirstLoop = true;

    Cam16 answer = null;
    while (Math.abs(low - high) >= CHROMA_SEARCH_ENDPOINT) {
      Cam16 possibleAnswer = findCamByJ(hue, mid, tone);

      if (isFirstLoop) {
        if (possibleAnswer != null) {
          return possibleAnswer.viewed(viewingConditions);
        } else {
          isFirstLoop = false;
          mid = low + (high - low) / 2.0;
          continue;
        }
      }

      if (possibleAnswer == null) {
        high = mid;
      } else {
        answer = possibleAnswer;
        low = mid;
      }

      mid = low + (high - low) / 2.0;
    }

    if (answer == null) {
      return ColorUtils.argbFromLstar(tone);
    }

    return answer.viewed(viewingConditions);
  }

  /**
   * @param hue CAM16 hue
   * @param chroma CAM16 chroma
   * @param tone L*a*b* lightness
   * @return CAM16 instance within error tolerance of the provided dimensions, or null.
   */
  private static Cam16 findCamByJ(double hue, double chroma, double tone) {
    double low = 0.0;
    double high = 100.0;
    double mid = 0.0;
    double bestdL = 1000.0;
    double bestdE = 1000.0;

    Cam16 bestCam = null;
    while (Math.abs(low - high) > LIGHTNESS_SEARCH_ENDPOINT) {
      mid = low + (high - low) / 2;
      Cam16 camBeforeClip = Cam16.fromJch(mid, chroma, hue);
      int clipped = camBeforeClip.toInt();
      double clippedLstar = ColorUtils.lstarFromArgb(clipped);
      double dL = Math.abs(tone - clippedLstar);

      if (dL < DL_MAX) {
        Cam16 camClipped = Cam16.fromInt(clipped);
        double dE =
            camClipped.distance(Cam16.fromJch(camClipped.getJ(), camClipped.getChroma(), hue));
        if (dE <= DE_MAX && dE <= bestdE) {
          bestdL = dL;
          bestdE = dE;
          bestCam = camClipped;
        }
      }

      if (bestdL == 0 && bestdE < DE_MAX_ERROR) {
        break;
      }

      if (clippedLstar < tone) {
        low = mid;
      } else {
        high = mid;
      }
    }

    return bestCam;
  }
}
