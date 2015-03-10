// ================================================================
//
// Disclaimer: IMPORTANT: This software was developed at the National
// Institute of Standards and Technology by employees of the Federal
// Government in the course of their official duties. Pursuant to
// title 17 Section 105 of the United States Code this software is not
// subject to copyright protection and is in the public domain. This
// is an experimental system. NIST assumes no responsibility
// whatsoever for its use by other parties, and makes no guarantees,
// expressed or implied, about its quality, reliability, or any other
// characteristic. We would appreciate acknowledgement if the software
// is used. This software can be redistributed and/or modified freely
// provided that any derivative works bear some notice that they are
// derived from it, and any modified versions bear some notice that
// they have been modified.
//
// ================================================================

// ================================================================
//
// Author: tjb3
// Date: Apr 11, 2014 11:15:45 AM EST
//
// Time-stamp: <Apr 11, 2014 11:15:45 AM tjb3>
//
//
// ================================================================

package gov.nist.isg.mist.stitching.lib.optimization;

import gov.nist.isg.mist.stitching.gui.executor.StitchingExecutor;
import gov.nist.isg.mist.stitching.lib.common.CorrelationTriple;
import gov.nist.isg.mist.stitching.lib.common.MinMaxElement;
import gov.nist.isg.mist.stitching.lib.exceptions.GlobalOptimizationException;
import gov.nist.isg.mist.stitching.lib.imagetile.ImageTile;
import gov.nist.isg.mist.stitching.lib.imagetile.Stitching;
import gov.nist.isg.mist.stitching.lib.log.Log;
import gov.nist.isg.mist.stitching.lib.log.Log.LogType;
import gov.nist.isg.mist.stitching.lib.statistics.StatisticUtils;
import gov.nist.isg.mist.stitching.lib.statistics.StatisticUtils.OP_TYPE;
import gov.nist.isg.mist.stitching.lib.tilegrid.TileGrid;

import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Utility class for doing various operations on a grid, such as per row or per column operations.
 * 
 * @author Tim Blattner
 * @version 1.0
 */
public class OptimizationUtils {

  private static final double CorrelationThreshold = 0.5;
  private static final int NumTopCorrelations = 5;
  private static final double CorrelationWeight = 4.0;
  public static OverlapType OVERLAP_TYPE = OverlapType.Heuristic;
  private static final double MLE_ACCURACY = 0.05;

  /**
   * The type of overlap computation
   *
   * @author Michael Majurski
   * @version 1.0
   */
  public enum OverlapType {
    /** Heuristic */
    Heuristic,
    /** Heuristic Full Std Search */
    HeuristicFullStd,
    /** MLE */
    MLE;
  }

  /**
   * The direction for optimization
   * 
   * @author Tim Blattner
   * @version 1.0
   */
  public enum Direction {
    /**
     * North
     */
    North,

    /**
     * West
     */
    West;
  }

  /**
   * The displacement value for optimization
   * 
   * @author Tim Blattner
   * @version 1.0
   */
  public enum DisplacementValue {
    
    /**
     * X
     */
    X,

    /**
     * Y
     */
    Y;
  }

  /**
   * Prints the values of a tile grid in a grid format similar to how Matlab displays values. Used
   * for comparison of Matlab and Java
   * 
   * @param grid the grid of image tiles
   * @param dir the direction (north or west) you want to display
   * @param dispVal the displacement value that is to be printed (X, Y, C)
   */
  public static <T> void printGrid(TileGrid<ImageTile<T>> grid, Direction dir,
      DisplacementValue dispVal) {
    Log.msgNoTime(LogType.MANDATORY, dir.name() + " : " + dispVal.name());

    for (int row = 0; row < grid.getExtentHeight(); row++) {
      for (int col = 0; col < grid.getExtentWidth(); col++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        CorrelationTriple triple = null;
        switch (dir) {
          case North:
            triple = tile.getNorthTranslation();
            break;
          case West:
            triple = tile.getWestTranslation();
            break;
          default:
            break;
        }

        String val = "0.0";

        if (triple != null) {
          switch (dispVal) {         
            case X:
              val = triple.getMatlabFormatStrX();
              break;
            case Y:
              val = triple.getMatlabFormatStrY();
              break;
            default:
              break;

          }
        }

        Log.msgnonlNoTime(LogType.MANDATORY, val + (col == grid.getExtentWidth() - 1 ? "" : ","));

      }
      Log.msgNoTime(LogType.MANDATORY, "");
    }

  }


  public static <T> List<CorrelationTriple> getTopCorrelationsFullStdCheck(TileGrid<ImageTile<T>> grid,
                                                               final Direction dir, int numCorrelations) throws FileNotFoundException {
    List<CorrelationTriple> topTranslations = new ArrayList<CorrelationTriple>(numCorrelations);
    List<ImageTile<T>> topTiles = new ArrayList<ImageTile<T>>();

    // gather all tiles with correlation above CorrelationThreshold into a list
    for (int row = 0; row < grid.getExtentHeight(); row++) {
      for (int col = 0; col < grid.getExtentWidth(); col++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);
        switch (dir) {
          case North:
            if (tile.getNorthTranslation() != null && tile.getNorthTranslation().getCorrelation() >= CorrelationThreshold)
              topTiles.add(tile);
            break;
          case West:
            if (tile.getWestTranslation() != null && tile.getWestTranslation().getCorrelation() >= CorrelationThreshold)
              topTiles.add(tile);
            break;
        }
      }
    }

    List<Double> stdDevList = new ArrayList<Double>();

    // Compute the top stdDev based on their x,y translation
    for (ImageTile<T> tile : topTiles) {
      ImageTile<T> neighbor = null;
      switch (dir) {
        case North:
          neighbor = grid.getTile(tile.getRow() - 1, tile.getCol());
          break;
        case West:
          neighbor = grid.getTile(tile.getRow(), tile.getCol() - 1);
          break;
      }

      if (neighbor == null)
        continue;

      switch (dir) {
        case North:
          tile.computeStdDevNorth(neighbor);
          stdDevList.add(tile.getLowestStdDevNorth());
          break;
        case West:
          tile.computeStdDevWest(neighbor);
          stdDevList.add(tile.getLowestStdDevWest());
          break;
      }
    }


    // create the new metric (correlation + normalized std)
    double a = Double.NEGATIVE_INFINITY;
    double b = Double.POSITIVE_INFINITY;
    for(Double v : stdDevList) {
      a = Math.max(a, v);
      b = Math.min(b, v);
    }
    final double maxStdVal = a;
    final double minStdVal = b;

    // Sort tiles based on correlation
    Collections.sort(topTiles, new Comparator<ImageTile<T>>() {
      @Override
      public int compare(ImageTile<T> t1, ImageTile<T> t2) {
        double v1;
        double v2;
        double c1;
        double c2;
        switch (dir) {
          case North:
            v1 = (t1.getLowestStdDevNorth() - minStdVal)/maxStdVal;
            v2 = (t2.getLowestStdDevNorth() - minStdVal)/maxStdVal;
            c1 = t1.getNorthTranslation().getCorrelation();
            c2 = t2.getNorthTranslation().getCorrelation();

            return Double.compare(v1+c1, v2+c2);
          case West:
            v1 = (t1.getLowestStdDevWest() - minStdVal)/maxStdVal;
            v2 = (t2.getLowestStdDevWest() - minStdVal)/maxStdVal;
            c1 = t1.getWestTranslation().getCorrelation();
            c2 = t2.getWestTranslation().getCorrelation();

            return Double.compare(v1+c1, v2+c2);
          default:
            return 0;
        }
      }
    });

    for (ImageTile<T> tile : topTiles) {
      switch (dir) {
        case North:
          tile.resetStdDevNorth();
          break;
        case West:
          tile.resetStdDevWest();
          break;
      }
    }


    // Get the top n tiles
    if (numCorrelations < topTiles.size()) {
      topTiles = topTiles.subList(topTiles.size() - numCorrelations, topTiles.size());
    }

    for (ImageTile<T> tile : topTiles) {
      switch (dir) {
        case North:
          topTranslations.add(tile.getNorthTranslation());
          break;
        case West:
          topTranslations.add(tile.getWestTranslation());
          break;
      }
    }

    Log.msg(LogType.VERBOSE, "Top translations:");
    for (CorrelationTriple t : topTranslations) {
      Log.msg(LogType.VERBOSE, t.toString());

    }

    return topTranslations;
  }


  /**
   * Gets the top correlations from a grid of tiles
   * 
   * @param grid the grid of image tiles
   * @param dir the direction (North or West)
   * @param numCorrelations the number of correlations
   * @return a list of correlations that are highest in the tile grid
   */
  public static <T> List<CorrelationTriple> getTopCorrelations(TileGrid<ImageTile<T>> grid,
      final Direction dir, int numCorrelations) throws FileNotFoundException  {
    List<CorrelationTriple> topTranslations = new ArrayList<CorrelationTriple>(numCorrelations);
    List<ImageTile<T>> topTiles = new ArrayList<ImageTile<T>>();

    // gather all tiles into a list
    for (int row = 0; row < grid.getExtentHeight(); row++) {
      for (int col = 0; col < grid.getExtentWidth(); col++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);
        switch (dir) {
          case North:
            if (tile.getNorthTranslation() != null)
              topTiles.add(tile);
            break;
          case West:
            if (tile.getWestTranslation() != null)
              topTiles.add(tile);
            break;
        }
      }
    }


    // Sort tiles based on correlation
    Collections.sort(topTiles, new Comparator<ImageTile<T>>() {
      @Override
      public int compare(ImageTile<T> t1, ImageTile<T> t2) {
        switch (dir) {
          case North:
            return Double.compare(t1.getNorthTranslation().getCorrelation(), t2
                .getNorthTranslation().getCorrelation());
          case West:
            return Double.compare(t1.getWestTranslation().getCorrelation(), t2.getWestTranslation()
                .getCorrelation());
          default:
            return 0;
        }
      }
    });

    // Get the top n tiles
    if (topTiles.size() < numCorrelations * 2) {
      numCorrelations = topTiles.size() < numCorrelations ? topTiles.size() : numCorrelations;
    } else {
      topTiles = topTiles.subList(topTiles.size() - numCorrelations * 2, topTiles.size());
    }

    List<Double> stdDevList = new ArrayList<Double>();

    // Compute the top stdDev based on their x,y translation
    for (ImageTile<T> tile : topTiles) {
      ImageTile<T> neighbor = null;
      switch (dir) {
        case North:
          neighbor = grid.getTile(tile.getRow() - 1, tile.getCol());
          break;
        case West:
          neighbor = grid.getTile(tile.getRow(), tile.getCol() - 1);
          break;
      }

      if (neighbor == null)
        continue;

      switch (dir) {
        case North:
          tile.computeStdDevNorth(neighbor);
          stdDevList.add(tile.getLowestStdDevNorth());
          break;
        case West:
          tile.computeStdDevWest(neighbor);
          stdDevList.add(tile.getLowestStdDevWest());
          break;
      }
    }

    double medStdDev = StatisticUtils.median(stdDevList);


    for (ImageTile<T> tile : topTiles) {
      switch (dir) {
        case North:
          if (tile.getLowestStdDevNorth() >= medStdDev)
            topTranslations.add(tile.getNorthTranslation());

          tile.resetStdDevNorth();

          break;
        case West:
          if (tile.getLowestStdDevWest() >= medStdDev)
            topTranslations.add(tile.getWestTranslation());

          tile.resetStdDevWest();

          break;
      }
    }

    Log.msg(LogType.VERBOSE, "Top translations:");
    for (CorrelationTriple t : topTranslations) {
      Log.msg(LogType.VERBOSE, t.toString());

    }

    return topTranslations;
  }

  /**
   * Computes the op on a list of correlations
   * 
   * @param corrList the list of correlations
   * @param dispVal the displacement value that is to be averaged
   * @param op the operation type
   * @return op(corrList), where op is max, mean, median,..., etc.
   */
  public static double computeOpTranslations(List<CorrelationTriple> corrList,
      DisplacementValue dispVal, OP_TYPE op) {
    List<Double> trans = new ArrayList<Double>();

    for (CorrelationTriple t : corrList) {
      switch (dispVal) {
        case Y:
          trans.add((double) t.getY());
          break;
        case X:
          trans.add((double) t.getX());
          break;
        default:
          break;
      }
    }

    switch (op) {
      case MAX:
        return StatisticUtils.max(trans);
      case MEAN:
        return StatisticUtils.mean(trans);
      case MEDIAN:
        return StatisticUtils.median(trans);
      case MIN:
        return StatisticUtils.min(trans);
      case MODE:
        return StatisticUtils.mode(trans);
      default:
        return StatisticUtils.median(trans);
    }

  }


  /**
   * Calculates the overlap for a given direction using Maximum Likelihood Estimation
   *
   * @param grid the grid of image tiles
   * @param dir the direction
   * @param dispValue the displacement value
   * @param pou the percent overlap uncertainty
   * @return the overlap
   * @throws GlobalOptimizationException thrown if no valid tiles are found
   */
  public static <T> double getOverlapMLE(TileGrid<ImageTile<T>> grid, Direction dir, DisplacementValue dispValue, double pou) throws GlobalOptimizationException, FileNotFoundException{
    Log.msg(LogType.INFO, "Computing overlap for " + dir.name() + " direction using Maximum Likelihood Estimation.");

    // get valid range for translations given the direction
    int range = getOverlapRange(grid, dispValue);

    List<Integer> translations = new ArrayList<Integer>();

    // gather all relevant translations into an array
    for (int row = 0; row < grid.getExtentHeight(); row++) {
      for (int col = 0; col < grid.getExtentWidth(); col++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);
        switch (dir) {
          case North:
            if (tile.getNorthTranslation() != null) {
              int t = 0;
              switch(dispValue) {
                case X:
                   t = tile.getNorthTranslation().getX();
                  break;
                case Y:
                  t = tile.getNorthTranslation().getY();
                  break;
              }
              if (t > 0 && t < range
                  && tile.getNorthTranslation().getCorrelation() >= CorrelationThreshold)
                translations.add(t);

            }
            break;
          case West:
            if (tile.getWestTranslation() != null) {
              int t = 0;
              switch(dispValue) {
                case X:
                  t = tile.getWestTranslation().getX();
                  break;
                case Y:
                  t = tile.getWestTranslation().getY();
                  break;
              }
              if (t > 0 && t < range
                  && tile.getWestTranslation().getCorrelation() >= CorrelationThreshold)
                translations.add(t);
            }
            break;
        }
      }
    }

    double overlap;
    try{
      overlap = getOverlapMLE(translations, range, pou);
    }catch(GlobalOptimizationException e) {
      throw new GlobalOptimizationException("Unable to compute overlap for " + dir.name() + ", translation list is empty.");
    }
    return overlap;

  }


  /**
   * Calculates the overlap for a given direction using Maximum Likelihood Estimation
   *
   * @param translations List of the translations to use in estimating overlap
   * @param range the valid range of translations
   * @param pou the percent overlap uncertainty
   * @return the overlap
   * @throws GlobalOptimizationException thrown if no valid tiles are found

   */
  public static double getOverlapMLE(List<Integer> translations, int range, double pou) throws GlobalOptimizationException {

    if (translations.size() < 1) {
      throw new GlobalOptimizationException("Unable to compute overlap, translation list is empty.");
    }

    // extract the translations into an primitive array
    double[] T = new double[translations.size()];
    for(int i = 0; i < translations.size(); i++)
        T[i] = translations.get(i);
    // allocate temp array to hold a malleable copy of T
    double[] x = new double[T.length];

    // determine MLE search space
    double nBins = 100/(100*MLE_ACCURACY);
    double pDelta = 1/nBins;
    int mDelta = (int) Math.max(1, Math.round(range/nBins));
    int sMax;
    if(Double.isNaN(pou)) {
      sMax = Math.round(range/2);
    }else {
      sMax = (int) Math.round((pou / 100) * range);
    }
    int sDelta = (int) Math.max(1, Math.round(sMax/nBins));

    // init MLE model parameters
    double PIuni = 0;
    double mu = 0;
    double sigma = 0;
    double MLE_acc = Double.NEGATIVE_INFINITY;

    // pre-compute constants
    double sqrt2pi = Math.sqrt(2*Math.PI);



    // perform exhaustive MLE search

    // loop over the PIuni range
    for(double p = 0; p <= 1; p=p+pDelta) {
      // loop over the mu range
      for(double m = 1; m <= range; m=m+mDelta) {

        // subtract current m from T array while copying data into x
        for(int i = 0; i < T.length; i++)
          x[i] = T[i] - m;

        // loop over sigma range
        for(double s = 1; s <= sMax; s=s+sDelta) {

          // loop over the elements the x array
          double sv = 0; // init sum value
          for(int i = 0; i < x.length; i++) {
            double temp = x[i] / s;
            temp = Math.exp(-0.5 * temp * temp);
            sv = sv + Math.log(Math.abs((p/range) + (1-p)*(temp / (sqrt2pi * s))));
          }

          // If the current probability is higher than the old max
          if(sv > MLE_acc) {
            PIuni = p;
            mu = m;
            sigma = s;
            MLE_acc = sv;
          }
        }
      }
    }


    // refine the MLE model parameters using hill climbing
    pDelta = 0.01;
    mDelta = 1;
    sDelta = 1;
    boolean done = false;
    while(!done) {

      // setup search bounds
      double pmin = Math.max(0,PIuni-pDelta);
      double pmax = Math.min(1, PIuni+pDelta);
      double mmin = Math.max(1,mu-mDelta);
      double mmax = Math.min(range,mu+mDelta);
      double smin = Math.max(1,sigma-sDelta);
      double smax = Math.min(sMax,sigma+sDelta);
      double mv = Double.NEGATIVE_INFINITY;

      // init temp copies of model parameters to hold local changes
      double tempPIuni = PIuni;
      double tempMu = mu;
      double tempSigma = sigma;


      // search 3x3x3 neighborhood for a better solution

      // loop over the PIuni range
      for(double p = pmin; p <= pmax; p=p+pDelta) {
        // loop over the mu range
        for (double m = mmin; m <= mmax; m=m+mDelta) {

          // subtract current m from T array, copying data to x
          for (int i = 0; i < T.length; i++)
            x[i] = T[i] - m;

          // loop over sigma range
          for (double s = smin; s <= smax; s=s+sDelta) {

            // loop over the elements the x array
            double sv = 0; // init sum value
            for(int i = 0; i < x.length; i++) {
              double temp = x[i] / s;
              temp = Math.exp(-0.5 * temp * temp);
              sv = sv + Math.log(Math.abs((p/range) + (1-p)*(temp / (sqrt2pi * s))));
            }

            // If the current probability is higher than the old max
            if(sv > mv) {
              tempPIuni = p;
              tempMu = m;
              tempSigma = s;
              mv = sv;
            }
          }
        }
      }

      // check if this hill climbing iteration found a better answer
      if(mv > MLE_acc) {
        PIuni = tempPIuni;
        mu = tempMu;
        sigma = tempSigma;
        MLE_acc = mv;
      }else{
        done = true;
      }
    }

    return Math.round(100*(range-mu)/range);
  }


  private static<T> int getOverlapRange(TileGrid<ImageTile<T>> grid, DisplacementValue dispValue) throws FileNotFoundException  {
    // get valid range for translations given the direction
    ImageTile<T> tile = grid.getSubGridTile(0, 0);
    if (!tile.isTileRead())
      tile.readTile();
    int range = 0;
    switch (dispValue) {
      case X:
        range = tile.getWidth();
        break;
      case Y:
        range = tile.getHeight();
        break;
    }
    return range;
  }

    /**
     * Calculates the overlap for a given direction
     *
     * @param grid the grid of image tiles
     * @param dir the direction
     * @param dispValue the displacement value
     * @param percOverlapError the overlap error
     * @return the overlap
     */
  public static <T> double getOverlap(TileGrid<ImageTile<T>> grid, Direction dir,
      DisplacementValue dispValue, double percOverlapError) throws FileNotFoundException  {
    Log.msg(LogType.VERBOSE,
        "Computing top " + NumTopCorrelations + " correlations for " + dir.name());


    int size = getOverlapRange(grid, dispValue);
    double med;
    List<CorrelationTriple> topCorrelations;
    double overlap = 0;
    switch(OVERLAP_TYPE) {
      case Heuristic:
        topCorrelations = getTopCorrelations(grid, dir, NumTopCorrelations);
        med = computeOpTranslations(topCorrelations, dispValue, OP_TYPE.MEDIAN);
        overlap = Math.round(100.0 * (1.0 - med / size));
        break;
      case HeuristicFullStd:
        topCorrelations = getTopCorrelationsFullStdCheck(grid, dir, NumTopCorrelations);
        med = computeOpTranslations(topCorrelations, dispValue, OP_TYPE.MEDIAN);
        overlap = Math.round(100.0 * (1.0 - med / size));
        break;
      case MLE:
        try {
          overlap = getOverlapMLE(grid, dir, dispValue, percOverlapError);
        } catch (GlobalOptimizationException e) {
          e.printStackTrace();
        }
        break;
    }

    return overlap;
  }

  /**
   * Copies north and western translations into 'preOptimization' north and western translations. This
   * saves the translations before optimization
   * 
   * @param grid the grid of image tiles
   */
  public static <T> void backupTranslations(TileGrid<ImageTile<T>> grid) {
    Log.msg(LogType.VERBOSE, "Backing up translations");

    for (int r = 0; r < grid.getExtentHeight(); r++) {
      for (int c = 0; c < grid.getExtentWidth(); c++) {
        ImageTile<T> tile = grid.getSubGridTile(r, c);
        if (tile.getNorthTranslation() != null)
          tile.setPreOptimizationNorthTranslation(tile.getNorthTranslation().clone());
        if (tile.getWestTranslation() != null)
          tile.setPreOptimizationWestTranslation(tile.getWestTranslation().clone());
      }
    }
  }

  /**
   * Filters grid of image tiles based on calculated overlap, correlation, and standard deviation.
   * A set of valid image tiles after filtering is returned.
   * 
   * @param grid the grid of image tiles
   * @param dir the direction that is to be filtered
   * @param percOverlapError the percent overlap of error
   * @param overlap the overlap between tiles
   * @return the list of valid image tiles
   */
  public static <T> HashSet<ImageTile<T>> filterTranslations(TileGrid<ImageTile<T>> grid,
      Direction dir, double percOverlapError, double overlap) throws FileNotFoundException  {
    Log.msg(LogType.INFO, "Filtering translations:");
    DisplacementValue dispValue = null;
    switch (dir) {
      case North:
        dispValue = DisplacementValue.Y;
        break;
      case West:
        dispValue = DisplacementValue.X;
        break;
      default:
        break;
    }
    
    HashSet<ImageTile<T>> overlapCorrFilter = filterTilesFromOverlapAndCorrelation(dir, dispValue, overlap, percOverlapError, grid);
       
    HashSet<ImageTile<T>> finalValidTiles = filterTilesFromStdDev(overlapCorrFilter, grid, dir, overlap, percOverlapError);
    
    Log.msg(LogType.VERBOSE, "Finished filter - valid tiles: " + finalValidTiles.size());

    return finalValidTiles;
  }

  
  private static <T> HashSet<ImageTile<T>> filterTilesFromOverlapAndCorrelation(Direction dir, DisplacementValue dispValue, double overlap, double
      percOverlapError, TileGrid<ImageTile<T>>grid) throws FileNotFoundException
  {    
    double minCorrelation = CorrelationThreshold;

    HashSet<ImageTile<T>> validTiles = new HashSet<ImageTile<T>>();

    double t_min = 0;
    double t_max = 0;

    ImageTile<T> initTile = grid.getSubGridTile(0, 0);
    if (!initTile.isTileRead())
      initTile.readTile();

    int width = initTile.getWidth();
    int height = initTile.getHeight();

    switch (dispValue) {
      case Y:
        t_min = height - (overlap + percOverlapError) * height / 100.0;

        t_max = height - (overlap - percOverlapError) * height / 100.0;
        break;

      case X:
        t_min = width - (overlap + percOverlapError) * width / 100.0;

        t_max = width - (overlap - percOverlapError) * width / 100.0;
        break;

      default:
        break;

    }

    StitchingExecutor.stitchingStatistics.setMinFilterThreshold(dir, t_min);
    StitchingExecutor.stitchingStatistics.setMaxFilterThreshold(dir, t_max);

    Log.msg(LogType.VERBOSE, "min,max threshold: " + t_min + "," + t_max);
    
    // Filter based on t_min, t_max, and minCorrelation
    for (int r = 0; r < grid.getExtentHeight(); r++) {
      for (int c = 0; c < grid.getExtentWidth(); c++) {
        ImageTile<T> tile = grid.getSubGridTile(r, c);

        CorrelationTriple triple = null;
        switch (dir) {
          case North:
            triple = tile.getNorthTranslation();
            break;
          case West:
            triple = tile.getWestTranslation();
            break;
          default:
            break;
        }

        if (triple == null)
          continue;

        if (triple.getCorrelation() < minCorrelation) {
          continue;
        }      

        switch (dispValue) {
          case Y:
            if (triple.getY() < t_min || triple.getY() > t_max) {
              continue;
            }
            break;
          case X:
            if (triple.getX() < t_min || triple.getX() > t_max) {
              continue;
            }
            break;
          default:
            break;

        }

        validTiles.add(tile);
      }
    }
    
    return validTiles;
    
  }
  
  private static <T> HashSet<ImageTile<T>> filterTilesFromStdDev(HashSet<ImageTile<T>> validTiles, 
      TileGrid<ImageTile<T>>grid, Direction dir, double overlap, double percOverlapError)
  {
    
    Log.msg(LogType.VERBOSE, "Filtering by standard deviation with " + validTiles.size()
        + " valid tiles");

    if (validTiles.size() == 0)
      return validTiles;

    // update pixel release counts for each tile
    for (ImageTile<T> tile : validTiles) {
      tile.setPixelDataReleaseCount(0);
    }

    for (ImageTile<T> tile : validTiles) {
      int row = tile.getRow();
      int col = tile.getCol();

      tile.incrementPixelDataReleaseCount();

      // check north
      if (row > grid.getStartRow()) {
        ImageTile<T> neighbor = grid.getTile(row - 1, col);
        if (validTiles.contains(neighbor)) {
          neighbor.incrementPixelDataReleaseCount();
          tile.incrementPixelDataReleaseCount();
        }

      }

      if (col > grid.getStartCol()) {
        ImageTile<T> neighbor = grid.getTile(row, col - 1);
        if (validTiles.contains(neighbor)) {
          neighbor.incrementPixelDataReleaseCount();
          tile.incrementPixelDataReleaseCount();
        }

      }
    }

    List<Double> stdDevValues = new ArrayList<Double>();

    // compute stdDev on direction in parallel.
    int numThreads = Runtime.getRuntime().availableProcessors();

    List<Thread> threads = new ArrayList<Thread>();

    BlockingQueue<ImageTile<T>> queue = new ArrayBlockingQueue<ImageTile<T>>(validTiles.size());
    queue.addAll(validTiles);

    for (int i = 0; i < numThreads; i++) {
      threads.add(new Thread(new StandardDeviationWorker<T>(queue, grid, dir, overlap,
          percOverlapError)));
    }

    for (Thread thread : threads)
      thread.start();

    for (Thread thread : threads) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        Log.msg(LogType.MANDATORY, e.getMessage());
      }
    }

    // Add standard deviations to list of StdDevs for computing the median
    for (ImageTile<T> tile : validTiles) {
      ImageTile<T> neighbor = null;

      switch (dir) {
        case North:
          neighbor = grid.getTile(tile.getRow() - 1, tile.getCol());
          break;
        case West:
          neighbor = grid.getTile(tile.getRow(), tile.getCol() - 1);
          break;
        default:
          break;

      }

      if (neighbor == null)
        continue;

      if (!grid.hasTile(neighbor)) {
        continue;
      }

      switch (dir) {
        case North:
          stdDevValues.add(tile.getStdDevNorthOverlapNeighbor());
          stdDevValues.add(tile.getStdDevNorthOverlapOrigin());
          break;
        case West:
          stdDevValues.add(tile.getStdDevWestOverlapNeighbor());
          stdDevValues.add(tile.getStdDevWestOverlapOrigin());
          break;
        default:
          break;

      }
      
            
    }

    double stdDevThreshold = StatisticUtils.median(stdDevValues);

    StitchingExecutor.stitchingStatistics.setStdDevThreshold(dir, stdDevThreshold);

    List<ImageTile<T>> removedTiles = new ArrayList<ImageTile<T>>();
    for (ImageTile<T> tile : validTiles) {
      switch (dir) {
        case North:
          if (tile.hasLowStdDevNorth(stdDevThreshold)) {
            removedTiles.add(tile);
          }

          break;
        case West:
          if (tile.hasLowStdDevWest(stdDevThreshold)) {
            removedTiles.add(tile);
          }
          break;
        default:
          break;

      }
    }

    if (validTiles.size() != removedTiles.size())
      validTiles.removeAll(removedTiles);

    return validTiles;
  }

  /**
   * Computes the min and max of the list of image tiles for a given direction and displacement
   * value
   * 
   * @param tiles the list of tiles
   * @param dir the direction
   * @param dispVal the displacement value to analyze
   * @return the min and max (MinMaxElement)
   */
  public static <T> MinMaxElement getMinMaxValidTiles(HashSet<ImageTile<T>> tiles, Direction dir,
      DisplacementValue dispVal) {
    int min = Integer.MAX_VALUE;
    int max = Integer.MIN_VALUE;

    for (ImageTile<T> t : tiles) {
      CorrelationTriple triple = null;

      switch (dir) {
        case North:
          triple = t.getNorthTranslation();
          break;
        case West:
          triple = t.getWestTranslation();
          break;
        default:
          break;

      }

      if (triple == null || Double.isNaN(triple.getCorrelation()))
        continue;

      switch (dispVal) {
        case X:
          if (min > triple.getX())
            min = triple.getX();

          if (max < triple.getX())
            max = triple.getX();

          break;
        case Y:
          if (min > triple.getY())
            min = triple.getY();

          if (max < triple.getY())
            max = triple.getY();

          break;
        default:
          break;

      }

    }

    return new MinMaxElement(min, max);

  }

  /**
   * Computes a list of min max elements, one for reach row in the grid of tiles for a given
   * direction and displacement value per row.
   * 
   * @param grid the grid of tiles
   * @param validTiles the set of valid tiles
   * @param dir the direction
   * @param dispVal the displacement value
   * @return a list of MinMaxElements, one per row.
   */
  public static <T> List<MinMaxElement> getMinMaxValidPerRow(TileGrid<ImageTile<T>> grid,
      HashSet<ImageTile<T>> validTiles, Direction dir, DisplacementValue dispVal) {

    List<MinMaxElement> minMaxPerRow = new ArrayList<MinMaxElement>(grid.getExtentHeight());

    for (int row = 0; row < grid.getExtentHeight(); row++) {

      int min = Integer.MAX_VALUE;
      int max = Integer.MIN_VALUE;

      for (int col = 0; col < grid.getExtentWidth(); col++) {
        // get tile on row
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        // If the tile we look at is not considered valid, then skip it
        if (!validTiles.contains(tile))
          continue;

        CorrelationTriple triple = null;

        switch (dir) {
          case North:
            triple = tile.getNorthTranslation();
            break;
          case West:
            triple = tile.getWestTranslation();
            break;
          default:
            break;
        }

        if (triple == null)
          continue;

        switch (dispVal) {
          case Y:
            if (min > triple.getY())
              min = triple.getY();

            if (max < triple.getY())
              max = triple.getY();
            break;
          case X:
            if (min > triple.getX())
              min = triple.getX();

            if (max < triple.getX())
              max = triple.getX();
            break;
          default:
            break;

        }

      }

      if (min != Integer.MAX_VALUE || max != Integer.MIN_VALUE)
        minMaxPerRow.add(new MinMaxElement(min, max));

    }
    return minMaxPerRow;
  }

  /**
   * Computes a list of min max elements, one for reach column in the grid of tiles for a given
   * direction and displacement value.
   * 
   * @param grid the grid of image tiles
   * @param validTiles the set of valid tiles
   * @param dir the direction
   * @param dispVal the displacement value to be operated on
   * @return the list of mins and maxes (MinMaxElement), one for each column of the grid
   */

  public static <T> List<MinMaxElement> getMinMaxValidPerCol(TileGrid<ImageTile<T>> grid,
      HashSet<ImageTile<T>> validTiles, Direction dir, DisplacementValue dispVal) {

    List<MinMaxElement> minMaxPerCol = new ArrayList<MinMaxElement>(grid.getExtentWidth());

    for (int col = 0; col < grid.getExtentWidth(); col++) {

      int min = Integer.MAX_VALUE;
      int max = Integer.MIN_VALUE;

      for (int row = 0; row < grid.getExtentHeight(); row++) {
        // get tile on row
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        if (!validTiles.contains(tile))
          continue;

        CorrelationTriple triple = null;

        switch (dir) {
          case North:
            triple = tile.getNorthTranslation();
            break;
          case West:
            triple = tile.getWestTranslation();
            break;
          default:
            break;
        }

        if (triple == null)
          continue;

        switch (dispVal) {
          case Y:
            if (min > triple.getY())
              min = triple.getY();

            if (max < triple.getY())
              max = triple.getY();
            break;
          case X:
            if (min > triple.getX())
              min = triple.getX();

            if (max < triple.getX())
              max = triple.getX();
            break;
          default:
            break;

        }
      }

      if (min != Integer.MIN_VALUE || max != Integer.MAX_VALUE)
        minMaxPerCol.add(new MinMaxElement(min, max));

    }
    return minMaxPerCol;
  }

  /**
   * Remove invalid translations that are less than 0.5 and not within the median per row for X and
   * Y. All translations that are not in range or have a correlation less than 0.5 have their
   * correlations set to NaN. All translations that are valid have their correlations incremented by
   * 4.0
   * 
   * @param grid the grid of image tiles
   * @param validTiles a set of valid tiles
   * @param repeatability the computed repeatability
   * @param dir the direction we are working with
   */
  public static <T> void removeInvalidTranslationsPerRow(TileGrid<ImageTile<T>> grid,
      HashSet<ImageTile<T>> validTiles, int repeatability, Direction dir) {
    // compute median X and Y values
    List<Double> medianXVals = new ArrayList<Double>();
    List<Double> medianYVals = new ArrayList<Double>();

    List<CorrelationTriple> validTriples = new ArrayList<CorrelationTriple>();
    for (int row = 0; row < grid.getExtentHeight(); row++) {
      validTriples.clear();

      for (int col = 0; col < grid.getExtentWidth(); col++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        if (!validTiles.contains(tile))
          continue;

        switch (dir) {
          case North:
            validTriples.add(tile.getNorthTranslation());
            break;
          case West:
            validTriples.add(tile.getWestTranslation());
            break;
          default:
            break;

        }
      }

      double medianRowX = Double.NaN;
      double medianRowY = Double.NaN;

      if (validTriples.size() > 0) {
        medianRowX = computeOp(validTriples, OP_TYPE.MEDIAN, DisplacementValue.X);

        medianRowY = computeOp(validTriples, OP_TYPE.MEDIAN, DisplacementValue.Y);
      }

      medianXVals.add(medianRowX);
      medianYVals.add(medianRowY);

    }

    // Filter based on repeatability and correlation
    for (int row = 0; row < grid.getExtentHeight(); row++) {
      for (int col = 0; col < grid.getExtentWidth(); col++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        CorrelationTriple triple = null;

        switch (dir) {
          case North:
            triple = tile.getNorthTranslation();
            break;
          case West:
            triple = tile.getWestTranslation();
            break;
          default:
            break;
        }

        if (triple == null)
          continue;

        if (Double.isNaN(medianXVals.get(row)) || Double.isNaN(medianYVals.get(row))) {
          triple.setCorrelation(Double.NaN);
          continue;
        }

        double xMin = medianXVals.get(row) - repeatability;
        double xMax = medianXVals.get(row) + repeatability;

        double yMin = medianYVals.get(row) - repeatability;
        double yMax = medianYVals.get(row) + repeatability;

        // If correlation is less than CorrelationThreshold
        // or outside x range or outside y range, then throw away
        if (triple.getCorrelation() < CorrelationThreshold || triple.getX() < xMin
            || triple.getX() > xMax || triple.getY() < yMin || triple.getY() > yMax) {
          if (validTiles.contains(tile))
            validTiles.remove(tile);

          triple.setCorrelation(Double.NaN);
        } else {
          triple.incrementCorrelation(CorrelationWeight);
          validTiles.add(tile);
        }
      }
    }
    
  }

  /**
   * Remove invalid translations that are less than 0.5 and not within the median per column for X
   * and Y. All translations that are not in range or have a correlation less than 0.5 have their
   * correlations set to NaN. All translations that are valid have their correlations incremented by
   * 4.0
   * 
   * @param grid the grid of image tiles
   * @param validTiles a set of valid tiles
   * @param repeatability the computed repeatability
   * @param dir the direction we are working with
   */
  public static <T> void removeInvalidTranslationsPerCol(TileGrid<ImageTile<T>> grid,
      HashSet<ImageTile<T>> validTiles, int repeatability, Direction dir) {
    // compute median X and Y values
    List<Double> medianXVals = new ArrayList<Double>();
    List<Double> medianYVals = new ArrayList<Double>();

    List<CorrelationTriple> validTriples = new ArrayList<CorrelationTriple>();
    for (int col = 0; col < grid.getExtentWidth(); col++) {
      validTriples.clear();

      for (int row = 0; row < grid.getExtentHeight(); row++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        if (!validTiles.contains(tile))
          continue;

        switch (dir) {
          case North:
            validTriples.add(tile.getNorthTranslation());
            break;
          case West:
            validTriples.add(tile.getWestTranslation());
            break;
          default:
            break;

        }
      }

      double medianColX = Double.NaN;
      double medianColY = Double.NaN;

      if (validTriples.size() > 0) {
        medianColX = computeOp(validTriples, OP_TYPE.MEDIAN, DisplacementValue.X);

        medianColY = computeOp(validTriples, OP_TYPE.MEDIAN, DisplacementValue.Y);
      }

      medianXVals.add(medianColX);
      medianYVals.add(medianColY);

    }

    // Filter based on repeatability and correlation
    for (int col = 0; col < grid.getExtentWidth(); col++) {
      for (int row = 0; row < grid.getExtentHeight(); row++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        CorrelationTriple triple = null;

        switch (dir) {
          case North:
            triple = tile.getNorthTranslation();
            break;
          case West:
            triple = tile.getWestTranslation();
            break;
          default:
            break;
        }

        if (triple == null)
          continue;

        if (Double.isNaN(medianXVals.get(col)) || Double.isNaN(medianYVals.get(col))) {
          triple.setCorrelation(Double.NaN);
          continue;
        }

        double xMin = medianXVals.get(col) - repeatability;
        double xMax = medianXVals.get(col) + repeatability;

        double yMin = medianYVals.get(col) - repeatability;
        double yMax = medianYVals.get(col) + repeatability;

        // If correlation is less than CorrelationThreshold
        // or outside x range or outside y range, then throw away
        if (triple.getCorrelation() < CorrelationThreshold || triple.getX() < xMin
            || triple.getX() > xMax || triple.getY() < yMin || triple.getY() > yMax) {
          if (validTiles.contains(tile))
            validTiles.remove(tile);

          triple.setCorrelation(Double.NaN);
        } else {
          triple.incrementCorrelation(CorrelationWeight);
          validTiles.add(tile);
        }
      }
    }

  }

  /**
   * Compute the repeatability for a min and max : ceil( (max - min) / 2.0). Typically this can be
   * called in conjunction with getMinMaxValidTiles
   * 
   * @param minMax the min max element
   * @return the repeatability for the min max element : ceil ( (max - min) / 2.0)
   */
  public static int getRepeatability(MinMaxElement minMax) {
    return (int) Math.ceil(((double) minMax.getMax() - (double) minMax.getMin()) / 2.0);
  }

  /**
   * Computes the repeatability for a list of mins and maxes. Typically this can be called in
   * conjunction with getMinMaxPerRow or getMinMaxPerCol
   * 
   * @param minMaxes the list of min max elements.
   * @return the repeatability
   */
  public static int getRepeatability(List<MinMaxElement> minMaxes) {
    List<Integer> vals = new ArrayList<Integer>();

    // compute ceil((max-min)/2) for each row/col
    for (int i = 0; i < minMaxes.size(); i++) {
      MinMaxElement minMax = minMaxes.get(i);
      if (minMax == null)
        continue;

      vals.add((int) Math.ceil(((double) minMax.getMax() - (double) minMax.getMin()) / 2.0));
    }

    return (int) StatisticUtils.max(vals);

  }

  /**
   * Fixes invalid translations in each row (translations that have NaN in their correlation).
   * op(validTranslationsPerRow) is used as the corrected translations. If an entire row is empty,
   * then it is added to the list of empty rows, which is returned.
   * 
   * @param grid the grid of image tiles
   * @param dir the direction
   * @param op the operation type (median, mode, max, ... etc.)
   * @return the list of rows that have no good translations
   */
  public static <T> List<Integer> fixInvalidTranslationsPerRow(TileGrid<ImageTile<T>> grid,
      Direction dir, OP_TYPE op) {
    List<Integer> emptyRows = new ArrayList<Integer>();
    List<CorrelationTriple> validCorrTriplesInRow = new ArrayList<CorrelationTriple>();
    List<CorrelationTriple> invalidCorrTriplesInRow = new ArrayList<CorrelationTriple>();

    // handle per row
    for (int row = 0; row < grid.getExtentHeight(); row++) {
      validCorrTriplesInRow.clear();
      invalidCorrTriplesInRow.clear();

      // First get all correlations that are not NaN
      for (int col = 0; col < grid.getExtentWidth(); col++) {

        ImageTile<T> tile = grid.getSubGridTile(row, col);
        CorrelationTriple triple = null;

        switch (dir) {
          case North:
            triple = tile.getNorthTranslation();
            break;
          case West:
            triple = tile.getWestTranslation();
            break;
          default:
            break;

        }

        if (triple == null)
          continue;

        if (Double.isNaN(triple.getCorrelation()))
          invalidCorrTriplesInRow.add(triple);
        else
          validCorrTriplesInRow.add(triple);

      }

      // Check for empty row
      if (validCorrTriplesInRow.size() == 0) {
        if (invalidCorrTriplesInRow.size() > 0)
          emptyRows.add(row);
      } else {
        CorrelationTriple optimizedTriple = computeOp(validCorrTriplesInRow, op);

        for (CorrelationTriple t : invalidCorrTriplesInRow) {
          t.setX(optimizedTriple.getX());
          t.setY(optimizedTriple.getY());
        }
      }
    }

    return emptyRows;
  }

  /**
   * Fixes invalid translations in each column (translations that have NaN in their correlation).
   * op(validTranslationsPerCol) is used as the corrected translations. If an entire column is
   * empty, then it is added to the list of empty columns, which is returned.
   * 
   * @param grid the grid of image tiles
   * @param dir the direction
   * @param op the operation type (median, mode, max, ... etc.)
   * @return the list of columns that have no good translations
   */
  public static <T> List<Integer> fixInvalidTranslationsPerCol(TileGrid<ImageTile<T>> grid,
      Direction dir, OP_TYPE op) {
    List<Integer> emptyCols = new ArrayList<Integer>();
    List<CorrelationTriple> validCorrTriplesInCol = new ArrayList<CorrelationTriple>();
    List<CorrelationTriple> invalidCorrTriplesInCol = new ArrayList<CorrelationTriple>();

    // handle per row
    for (int col = 0; col < grid.getExtentWidth(); col++) {
      validCorrTriplesInCol.clear();
      invalidCorrTriplesInCol.clear();

      // First get all correlations that are not NaN
      for (int row = 0; row < grid.getExtentHeight(); row++) {

        ImageTile<T> tile = grid.getSubGridTile(row, col);
        CorrelationTriple triple = null;

        switch (dir) {
          case North:
            triple = tile.getNorthTranslation();
            break;
          case West:
            triple = tile.getWestTranslation();
            break;
          default:
            break;

        }

        if (triple == null)
          continue;

        if (Double.isNaN(triple.getCorrelation()))
          invalidCorrTriplesInCol.add(triple);
        else
          validCorrTriplesInCol.add(triple);

      }

      // Check for empty column
      if (validCorrTriplesInCol.size() == 0) {
        if (invalidCorrTriplesInCol.size() > 0)
          emptyCols.add(col);
      } else {
        CorrelationTriple optimizedTriple = computeOp(validCorrTriplesInCol, op);

        for (CorrelationTriple t : invalidCorrTriplesInCol) {
          t.setX(optimizedTriple.getX());
          t.setY(optimizedTriple.getY());
        }
      }

    }

    return emptyCols;
  }


  public static <T> void replaceTranslationFromOverlap(TileGrid<ImageTile<T>> grid, Direction dir, DisplacementValue dispValue, double overlap) throws FileNotFoundException {

    int estTranslation;
    switch(dir) {
      case West:
        estTranslation = (int) Math.round(OptimizationUtils.getOverlapRange(grid, dispValue)*(1 - overlap/100));
        for (int col = 0; col < grid.getExtentWidth(); col++) {
          for (int row = 0; row < grid.getExtentHeight(); row++) {
            CorrelationTriple triple = grid.getSubGridTile(row, col).getWestTranslation();
            if(triple != null) {
              triple.setX(estTranslation);
              triple.setY(0);
            }
          }
        }


        break;
      case North:
        estTranslation = (int) Math.round(OptimizationUtils.getOverlapRange(grid, dispValue)*(1 - overlap/100));

        for (int col = 0; col < grid.getExtentWidth(); col++) {
          for (int row = 0; row < grid.getExtentHeight(); row++) {
            CorrelationTriple triple = grid.getSubGridTile(row, col).getNorthTranslation();
            if(triple != null) {
              triple.setX(0);
              triple.setY(estTranslation);
            }
          }
        }

        break;
    }

  }
  
  public static <T> void replaceTranslationsRow(TileGrid<ImageTile<T>> grid, CorrelationTriple median, List<Integer> missingRows)
  {
    for (int row : missingRows)
    {
      for (int col = 0; col < grid.getExtentWidth(); col++)
      {
        ImageTile<T> tile = grid.getSubGridTile(row, col);
        
        CorrelationTriple triple = tile.getNorthTranslation();
        triple.setX(median.getX());
        triple.setY(median.getY());                 
      }
    }
  }
  
  public static <T> void replaceTranslationsCol(TileGrid<ImageTile<T>> grid, CorrelationTriple median, List<Integer> missingCols)
  {
    for (int col : missingCols)
    {
      for (int row = 0; row < grid.getExtentHeight(); row++)
      {
        ImageTile<T> tile = grid.getSubGridTile(row, col);
        
        CorrelationTriple triple = tile.getWestTranslation();
        triple.setX(median.getX());
        triple.setY(median.getY());                 
      }
    }
  }
  
  /**
   * Updates missing rows using the highest standard deviation tile as a reference for the search.
   * The window of the search is based on the minimum and maximum translations for all
   * valid tiles. Using the min/max we create a backlash measurement to search around. 
   * Using the backlash we use hill climbing to find the best correlation.
   * Using this correlation we update the entire row's x/y displacement.
   * This is done for every row of tiles that have no good translations.
   * 
   * @param grid the grid of tiles
   * @param overlap the computed overlap
   * @param percOverlapError the overlap error
   * @param repeatability the repeatability
   * @param min the minimum X,Y translation of all image tiles
   * @param max the maximum X,Y translation of all image tiles
   * @param missingRows a list of missing rows
   */
  public static <T> void updateEmptyRows(TileGrid<ImageTile<T>> grid, double overlap,
      double percOverlapError, int repeatability, CorrelationTriple min, CorrelationTriple max,
      List<Integer> missingRows) throws FileNotFoundException  {

    for (int row : missingRows) {
      // For each tile in the row, find the tile that has the highest
      // standard deviation
      ImageTile<T> highestStdDevTile = null;

      for (int col = 0; col < grid.getExtentWidth(); col++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        if (tile == null || tile.getNorthTranslation() == null)
          continue;

        ImageTile<T> neighbor = grid.getSubGridTile(row - 1, col);

        if (!neighbor.isTileRead())
          neighbor.readTile();

        if (!tile.isTileRead())
          tile.readTile();

        tile.computeStdDevNorth(neighbor, overlap, percOverlapError);

        if (highestStdDevTile == null)
          highestStdDevTile = tile;
        else if (tile.getLowestStdDevNorth() > highestStdDevTile.getLowestStdDevNorth())
          highestStdDevTile = tile;

      }
      
      if (highestStdDevTile == null)
      {
        Log.msg(LogType.MANDATORY, "Error finding highest standard deviation tile. "
            + "Using column 0 tile.");
        highestStdDevTile = grid.getSubGridTile(row, 0);
      }

      // do exhaustive search for tile
      int yMin = min.getY() - repeatability;
      int yMax = max.getY() + repeatability;

      int xMin = min.getX() - repeatability;
      int xMax = max.getX() + repeatability;

      ImageTile<T> neighbor =
          grid.getTile(highestStdDevTile.getRow() - 1, highestStdDevTile.getCol());
      CorrelationTriple bestNorth;

      if (!neighbor.isTileRead())
        neighbor.readTile();

      if (!highestStdDevTile.isTileRead())
        highestStdDevTile.readTile();

      try {
        if (Stitching.USE_HILLCLIMBING) {
          int startX = (int) (Math.round(((double) xMax - (double) xMin) / 2.0)) + xMin;
          int startY = (int) (Math.round(((double) yMax - (double) yMin) / 2.0)) + yMin;
          bestNorth =
              Stitching.computeCCF_HillClimbing_UD(xMin, xMax, yMin, yMax, startX, startY,
                  neighbor, highestStdDevTile);
        } else {
          bestNorth = Stitching.computeCCF_UD(xMin, xMax, yMin, yMax, neighbor, highestStdDevTile);
        }
      } catch (NullPointerException e) {
        // Catch if the user cancelled the execution. If they have then the tile
        // data will be null
        return;
      }

      for (int col = 0; col < grid.getExtentWidth(); col++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        if (tile == null || tile.getNorthTranslation() == null)
          continue;

        tile.getNorthTranslation().setX(bestNorth.getX());
        tile.getNorthTranslation().setY(bestNorth.getY());

        tile.releasePixels();
      }

    }
  }

  /**
   * Updates missing columns using the highest standard deviation tile as a reference for the search.
   * The window of the search is based on the minimum and maximum translations for all
   * valid tiles. Using the min/max we create a backlash measurement to search around. 
   * Using the backlash we use hill climbing to find the best correlation.
   * Using this correlation we update the entire column's x/y displacement.
   * This is done for every column of tiles that have no good translations.
   * 
   * @param grid the grid of tiles
   * @param overlap the computed overlap
   * @param percOverlapError the overlap error
   * @param repeatability the repeatability
   * @param min the minimum X,Y translation of all valid image tiles
   * @param max the maximum X,Y translation of all valid image tiles
   * @param missingCols a list of missing columns
   */
  public static <T> void updateEmptyColumns(TileGrid<ImageTile<T>> grid, double overlap,
      double percOverlapError, int repeatability, CorrelationTriple min, CorrelationTriple max,
      List<Integer> missingCols) throws FileNotFoundException {
    for (int col : missingCols) {
      // For each tile in the col, find the tile that has the highest
      // standard deviation
      ImageTile<T> highestStdDevTile = null;

      for (int row = 0; row < grid.getExtentHeight(); row++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        if (tile == null || tile.getWestTranslation() == null)
          continue;

        ImageTile<T> neighbor = grid.getSubGridTile(row, col - 1);

        if (!tile.isTileRead())
          tile.readTile();

        if (!neighbor.isTileRead())
          neighbor.readTile();

        tile.computeStdDevWest(neighbor, overlap, percOverlapError);

        if (highestStdDevTile == null)
          highestStdDevTile = tile;
        else if (tile.getLowestStdDevWest() > highestStdDevTile.getLowestStdDevWest())
          highestStdDevTile = tile;

      }
      
      if (highestStdDevTile == null)
      {
        Log.msg(LogType.MANDATORY, "Error finding highest standard deviation tile. "
            + "Using row 0 tile.");
        highestStdDevTile = grid.getSubGridTile(0, col);
      }

      // do exhaustive search for tile with highest standard deviation
      int yMin = min.getY() - repeatability;
      int yMax = max.getY() + repeatability;

      int xMin = min.getX() - repeatability;
      int xMax = max.getX() + repeatability;

      ImageTile<T> neighbor =
          grid.getTile(highestStdDevTile.getRow(), highestStdDevTile.getCol() - 1);

      CorrelationTriple bestWest;

      if (!highestStdDevTile.isTileRead())
        highestStdDevTile.readTile();

      if (!neighbor.isTileRead())
        neighbor.readTile();

      try {
        if (Stitching.USE_HILLCLIMBING) {
          int startX = (int) (Math.round(((double) xMax - (double) xMin) / 2.0)) + xMin;
          int startY = (int) (Math.round(((double) yMax - (double) yMin) / 2.0)) + yMin;
          bestWest =
              Stitching.computeCCF_HillClimbing_LR(xMin, xMax, yMin, yMax, startX, startY,
                  neighbor, highestStdDevTile);
        } else {
          bestWest = Stitching.computeCCF_LR(xMin, xMax, yMin, yMax, neighbor, highestStdDevTile);
        }
      } catch (NullPointerException e) {
        // Catch if the user cancelled the execution. If they have then the tile
        // data will be null
        return;
      }

      for (int row = 0; row < grid.getExtentHeight(); row++) {
        ImageTile<T> tile = grid.getSubGridTile(row, col);

        if (tile == null || tile.getWestTranslation() == null)
          continue;

        tile.getWestTranslation().setX(bestWest.getX());
        tile.getWestTranslation().setY(bestWest.getY());

        tile.releasePixels();

      }
    }
  }

  /**
   * Computes an operation on a list of correlation triples (one for both X and Y)
   * 
   * @param vals the list of correlation triples
   * @param op the operation type
   * @return the correlation values s.t. corrTriple.x = op(vals.x), corrTriple.y = op(vals.y), and
   *         corrTriple.corr is irrelevant
   */
  public static CorrelationTriple computeOp(List<CorrelationTriple> vals, OP_TYPE op) {
    List<Double> xVals = new ArrayList<Double>();
    List<Double> yVals = new ArrayList<Double>();

    for (CorrelationTriple t : vals) {
      xVals.add((double) t.getX());
      yVals.add((double) t.getY());
    }

    double xOptimized = 0.0;
    double yOptimized = 0.0;

    switch (op) {
      case MAX:
        xOptimized = StatisticUtils.max(xVals);
        yOptimized = StatisticUtils.max(yVals);
        break;
      case MEAN:
        xOptimized = StatisticUtils.mean(xVals);
        yOptimized = StatisticUtils.mean(yVals);
        break;
      case MEDIAN:
        xOptimized = StatisticUtils.median(xVals);
        yOptimized = StatisticUtils.median(yVals);
        break;
      case MIN:
        xOptimized = StatisticUtils.min(xVals);
        yOptimized = StatisticUtils.min(yVals);
        break;
      case MODE:
        xOptimized = StatisticUtils.mode(xVals);
        yOptimized = StatisticUtils.mode(yVals);
        break;
      default:
        break;

    }

    xOptimized = Math.round(xOptimized);
    yOptimized = Math.round(yOptimized);

    return new CorrelationTriple(Double.NaN, (int) xOptimized, (int) yOptimized);
  }

  /**
   * Computes an operation on a list of correlation triples for the given displacement value.
   * 
   * @param vals the list of correlation triples
   * @param op the operation type
   * @param dispVal the displacement value that is operated on
   * @return op(vals.dispVal)
   */
  public static double computeOp(List<CorrelationTriple> vals, OP_TYPE op, DisplacementValue dispVal) {
    List<Double> tVals = new ArrayList<Double>();

    for (CorrelationTriple t : vals) {
      switch (dispVal) {
        case X:
          tVals.add((double) t.getX());
          break;
        case Y:
          tVals.add((double) t.getY());
          break;
        default:
          break;

      }
    }

    double opResult = 0.0;

    switch (op) {
      case MAX:
        opResult = StatisticUtils.max(tVals);
        break;
      case MEAN:
        opResult = StatisticUtils.mean(tVals);
        break;
      case MEDIAN:
        opResult = StatisticUtils.median(tVals);
        break;
      case MIN:
        opResult = StatisticUtils.min(tVals);
        break;
      case MODE:
        opResult = StatisticUtils.mode(tVals);
        break;
      default:
        break;

    }
    return opResult;
  }

  /**
   * Computes an operation on a list of ImageTiles (one for both X and Y) for a given direction.
   * 
   * @param tiles the list of image tiles
   * @param dir the direction
   * @param op the operation type
   * @return the correlation values s.t. corrTriple.x = op(vals.x), corrTriple.y = op(vals.y), and
   *         corrTriple.corr is irrelevant
   */
  public static <T> CorrelationTriple computeOp(List<ImageTile<T>> tiles, Direction dir, OP_TYPE op) {
    List<CorrelationTriple> corrList = new ArrayList<CorrelationTriple>();

    for (ImageTile<T> t : tiles) {
      switch (dir) {
        case North:
          corrList.add(t.getNorthTranslation());
          break;
        case West:
          corrList.add(t.getWestTranslation());
          break;
        default:
          break;

      }
    }

    return computeOp(corrList, op);

  }

  /**
   * Computes an operation on a set of ImageTiles (one for both X and Y) for a given direction.
   * 
   * @param tiles the set of image tiles
   * @param dir the direction
   * @param op the operation type
   * @return the correlation values s.t. corrTriple.x = op(vals.x), corrTriple.y = op(vals.y), and
   *         corrTriple.corr is irrelevant
   */
  public static <T> CorrelationTriple computeOp(HashSet<ImageTile<T>> tiles, Direction dir,
      OP_TYPE op) {
    List<CorrelationTriple> corrList = new ArrayList<CorrelationTriple>();

    for (ImageTile<T> t : tiles) {
      switch (dir) {
        case North:
          corrList.add(t.getNorthTranslation());
          break;
        case West:
          corrList.add(t.getWestTranslation());
          break;
        default:
          break;

      }
    }

    return computeOp(corrList, op);

  }

}