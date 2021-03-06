/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.cdmr.client;

import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.grid.*;
import ucar.nc2.internal.util.CompareArrayToArray;
import ucar.nc2.time.CalendarDate;
import ucar.unidata.util.test.TestDir;
import ucar.unidata.util.test.category.NeedsExternalResource;
import ucar.unidata.util.test.category.NotJenkins;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/** Test {@link CdmrNetcdfFile} */
@RunWith(Parameterized.class)
@Category({NeedsExternalResource.class, NotJenkins.class}) // Needs CmdrServer to be started up
public class TestCdmrGridDataset {
  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> getTestParameters() {
    List<Object[]> result = new ArrayList<>(500);
    try {
      TestDir.actOnAllParameterized(TestDir.cdmLocalFromTestDataDir, new SuffixFileFilter(".nc"), result, true);

      // result.add(new Object[] {TestDir.cdmLocalFromTestDataDir + "permuteTest.nc"});

    } catch (Exception e) {
      e.printStackTrace();
    }
    return result;
  }

  private final String filename;
  private final String cdmrUrl;

  public TestCdmrGridDataset(String filename) throws IOException {
    this.filename = filename.replace("\\", "/");
    File file = new File(filename);
    System.out.printf("getAbsolutePath %s%n", file.getAbsolutePath());
    System.out.printf("getCanonicalPath %s%n", file.getCanonicalPath());

    // LOOK kludge for now. Also, need to auto start up CmdrServer
    this.cdmrUrl = "cdmr://localhost:16111/" + file.getCanonicalPath();
  }

  @Test
  public void doOne() throws Exception {
    Formatter info = new Formatter();
    try (GridDataset local = GridDatasetFactory.openGridDataset(filename, info)) {
      if (local == null) {
        System.out.printf("TestCdmrNetcdfFile %s NOT a grid%n", filename);
        return;
      }
      System.out.printf("TestCdmrNetcdfFile call server for %s%n", filename);
      try (GridDataset remote = GridDatasetFactory.openGridDataset(cdmrUrl, info)) {
        assertThat(remote).isNotNull();
        boolean ok = compareGridDataset(local, remote);
        if (!ok) {
          System.out.printf("infp = '%s'%n", info);
        }
        assertThat(ok).isTrue();

        for (Grid localGrid : local.getGrids()) {
          remote.findGrid(localGrid.getName()).ifPresent(remoteGrid -> compareGrid(localGrid, remoteGrid));
        }
      }
    }
  }

  private boolean compareGridDataset(GridDataset local, GridDataset remote) {
    System.out.printf("local (%s) = %s%n%n", local.getClass().getName(), local);
    System.out.printf("====================================================%n");
    System.out.printf("remote (%s) = %s%n%n", remote.getClass().getName(), remote);
    System.out.printf("====================================================%n");

    boolean ok = true;

    assertThat(local.getName()).isEqualTo(remote.getName());
    assertThat(local.getFeatureType()).isEqualTo(remote.getFeatureType());

    for (GridCoordinateSystem gcs : local.getGridCoordinateSystems()) {
      GridCoordinateSystem rcs = remote.getGridCoordinateSystems().stream()
          .filter(cs -> cs.getName().equals(gcs.getName())).findFirst().orElse(null);
      assertThat(rcs).isNotNull();
      assertWithMessage(gcs.getName()).that(rcs).isEqualTo(gcs);
    }

    for (GridAxis axis : local.getGridAxes()) {
      GridAxis raxis =
          remote.getGridAxes().stream().filter(cs -> cs.getName().equals(axis.getName())).findFirst().orElse(null);
      assertThat(raxis).isNotNull();
      assertWithMessage(axis.getName()).that(raxis).isEqualTo(axis);
    }

    for (Grid grid : local.getGrids()) {
      Grid rgrid =
          remote.getGrids().stream().filter(cs -> cs.getName().equals(grid.getName())).findFirst().orElse(null);
      assertThat(rgrid).isNotNull();
      assertWithMessage(grid.getName()).that(rgrid.getCoordinateSystem()).isEqualTo(grid.getCoordinateSystem());
    }

    return ok;
  }

  private static boolean compareGrid(Grid local, Grid remote) {
    GridSubset subset = new GridSubset();
    try {
      return doRunTime(local, remote, subset);
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  private static boolean doRunTime(Grid local, Grid remote, GridSubset subset) throws Exception {
    boolean ok = true;
    GridAxis1DTime runtimeAxis = local.getCoordinateSystem().getRunTimeAxis();
    if (runtimeAxis == null) {
      ok &= doTime(local, remote, subset);
    } else {
      for (CalendarDate coord : runtimeAxis.getCalendarDates()) {
        subset.setRunTime(coord);
        ok &= doTime(local, remote, subset);
      }
    }
    return ok;
  }


  private static boolean doTime(Grid local, Grid remote, GridSubset subset) throws Exception {
    boolean ok = true;
    GridAxis timeAxis = local.getCoordinateSystem().getTimeOffsetAxis();
    if (timeAxis == null) {
      timeAxis = local.getCoordinateSystem().getTimeAxis();
    }
    if (timeAxis == null) {
      ok &= doVert(local, remote, subset);
    } else {
      for (Object coord : timeAxis) {
        subset.setTimeCoord(coord);
        ok &= doVert(local, remote, subset);
      }
    }
    return ok;
  }

  private static boolean doVert(Grid local, Grid remote, GridSubset subset) throws Exception {
    boolean ok = true;
    GridAxis1D vertAxis = local.getCoordinateSystem().getVerticalAxis();
    if (vertAxis == null) {
      ok &= doSubset(local, remote, subset);
    } else {
      for (Object vertCoord : vertAxis) {
        subset.setVertCoord(vertCoord);
        ok &= doSubset(local, remote, subset);
      }
    }
    return ok;
  }

  private static boolean doSubset(Grid local, Grid remote, GridSubset subset)
      throws IOException, InvalidRangeException {
    System.out.printf(" Grid %s subset %s %n", local.getName(), subset);
    GridReferencedArray localArray = local.readData(subset);
    GridReferencedArray remoteArray = remote.readData(subset);
    Formatter f = new Formatter();
    boolean ok1 =
        CompareArrayToArray.compareData(f, local.getName(), localArray.data(), remoteArray.data(), true, true);
    if (!ok1) {
      System.out.printf("  FAIL %s%n", f);
    }
    return ok1;
  }

}
