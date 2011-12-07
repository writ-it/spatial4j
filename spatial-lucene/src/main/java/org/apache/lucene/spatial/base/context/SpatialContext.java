/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.spatial.base.context;

import org.apache.lucene.spatial.base.distance.*;
import org.apache.lucene.spatial.base.exception.InvalidShapeException;
import org.apache.lucene.spatial.base.shape.Circle;
import org.apache.lucene.spatial.base.shape.Point;
import org.apache.lucene.spatial.base.shape.Rectangle;
import org.apache.lucene.spatial.base.shape.Shape;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.StringTokenizer;

/**
 * This holds things like distance units, distance calculator, and world bounds.
 * Threadsafe & immutable.
 */
public abstract class SpatialContext {

  private final DistanceUnits units;
  private final DistanceCalculator calculator;
  private final Rectangle worldBounds;

  protected SpatialContext(DistanceUnits units, DistanceCalculator calculator) {
    this(units,calculator,null);
  }

  protected SpatialContext(DistanceUnits units, DistanceCalculator calculator, Rectangle worldBounds) {
    if (units == null) {
      if (calculator != null)
        throw new IllegalArgumentException("if units is null, calculator must be defaulted via null too.");
      units = DistanceUnits.KILOMETERS;
    }
    if (calculator == null) {
      if (units == DistanceUnits.EUCLIDEAN)
        calculator = new EuclideanDistanceCalculator();
      else
        calculator = new HaversineDistanceCalculator(units.earthRadius());
    }
    this.units = units;
    this.calculator = calculator;
    //TODO DWS: I don't like the way I initialize worldBounds, but it'll do for now.
    if (worldBounds == null) {
      switch(units) {
        case KILOMETERS:
        case MILES:
          worldBounds = makeRect(-180,180,-90,90);
          break;
        case EUCLIDEAN:
          double v = Double.MAX_VALUE;
          worldBounds = makeRect(-v, v,-v, v);
          break;
        default:
          throw new IllegalStateException("Unknown unit to calc world bounds: "+units);
      }
    } else {
      //copy so we can ensure we have the right implementation
      worldBounds = makeRect(worldBounds.getMinX(),worldBounds.getMaxX(),worldBounds.getMinY(),worldBounds.getMaxY());
      //there are other assumptions in the framework that assume geo == WGS84 essentially so I'll assume that here.
      if (isGeo())
        assert worldBounds.equals(makeRect(-180,180,-90,90));
    }
    this.worldBounds = worldBounds;
    if (worldBounds.getCrossesDateLine())
      throw new IllegalArgumentException("worldbounds shouldn't cross dateline: "+worldBounds);
  }

  public DistanceUnits getUnits() {
    return units;
  }

  public DistanceCalculator getDistanceCalculator() {
    return calculator;
  }

  public Rectangle getWorldBounds() {
    return worldBounds;
  }

  public double normX(double x) {
    if (isGeo()) {
      return DistanceUtils.normLonDEG(x);
    } else {
      return x;
    }
  }

  public double normY(double y) {
    if (isGeo()) {
      return DistanceUtils.normLatDEG(y);
    } else {
      return y;
    }
  }

  /**
   * Is this a geospatial context (true) or simply 2d spatial (false)
   * @return
   */
  public boolean isGeo() {
    return getUnits() != DistanceUnits.EUCLIDEAN;
  }

  /**
   * Read a shape from a given string (ie, X Y, XMin XMax... WKT)
   *
   * (1) Point: X Y
   *   1.23 4.56
   *
   * (2) BOX: XMin YMin XMax YMax
   *   1.23 4.56 7.87 4.56
   *
   * (3) WKT
   *   POLYGON( ... )
   *   http://en.wikipedia.org/wiki/Well-known_text
   *
   */
  public abstract Shape readShape(String value) throws InvalidShapeException;

  public Point readLatCommaLonPoint(String value) throws InvalidShapeException {
    double[] latLon = DistanceUtils.parseLatitudeLongitude(value);
    return makePoint(latLon[1],latLon[0]);
  }

  public abstract String toString(Shape shape);

  /** Construct a point. The parameters will be normalized. */
  public abstract Point makePoint( double x, double y );

  /** Construct a rectangle. The parameters will be normalized. */
  public abstract Rectangle makeRect(double minX, double maxX, double minY, double maxY);

  /** Construct a circle. The parameters will be normalized. */
  public Circle makeCircle(double x, double y, double distance) {
    return makeCircle(makePoint(x,y),distance);
  }

  /**
   *
   * @param ctr
   * @param distance The units of "distance" should be the same as {@link #getUnits()}.
   * @return
   */
  public abstract Circle makeCircle(Point ctr, double distance);

  protected Shape readStandardShape(String str) {
    if (str.length() < 1) {
      throw new InvalidShapeException(str);
    }

    if(Character.isLetter(str.charAt(0))) {
      if( str.startsWith( "Circle(" ) ) {
        int idx = str.lastIndexOf( ')' );
        if( idx > 0 ) {
          String body = str.substring( "Circle(".length(), idx );
          StringTokenizer st = new StringTokenizer(body, " ");
          String token = st.nextToken();
          Point pt;
          if (token.indexOf(',') != -1) {
            pt = readLatCommaLonPoint(token);
          } else {
            double x = Double.parseDouble(token);
            double y = Double.parseDouble(st.nextToken());
            pt = makePoint(x,y);
          }
          Double d = null;

          String arg = st.nextToken();
          idx = arg.indexOf( '=' );
          if( idx > 0 ) {
            String k = arg.substring( 0,idx );
            if( k.equals( "d" ) || k.equals( "distance" ) ) {
              d = Double.parseDouble( arg.substring(idx+1));
            }
            else {
              throw new InvalidShapeException( "unknown arg: "+k+" :: " +str );
            }
          }
          else {
            d = Double.parseDouble(arg);
          }
          if( st.hasMoreTokens() ) {
            throw new InvalidShapeException( "Extra arguments: "+st.nextToken()+" :: " +str );
          }
          if( d == null ) {
            throw new InvalidShapeException( "Missing Distance: "+str );
          }
          //NOTE: we are assuming the units of 'd' is the same as that of the spatial context.
          return makeCircle(pt, d);
        }
      }
      return null;
    }

    if (str.indexOf(',') != -1)
      return readLatCommaLonPoint(str);
    StringTokenizer st = new StringTokenizer(str, " ");
    double p0 = Double.parseDouble(st.nextToken());
    double p1 = Double.parseDouble(st.nextToken());
    if (st.hasMoreTokens()) {
      double p2 = Double.parseDouble(st.nextToken());
      double p3 = Double.parseDouble(st.nextToken());
      if (st.hasMoreTokens())
        throw new InvalidShapeException("Only 4 numbers supported (rect) but found more: "+str);
      return makeRect(p0, p2, p1, p3);
    }
    return makePoint(p0, p1);
  }

  public String writeRect(Rectangle rect) {
    NumberFormat nf = NumberFormat.getInstance(Locale.US);
    nf.setGroupingUsed(false);
    nf.setMaximumFractionDigits(6);
    nf.setMinimumFractionDigits(6);

    return
      nf.format(rect.getMinX()) + " " +
      nf.format(rect.getMinY()) + " " +
      nf.format(rect.getMaxX()) + " " +
      nf.format(rect.getMaxY());
  }
}