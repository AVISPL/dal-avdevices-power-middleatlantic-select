/*
 * Copyright (c) 2020 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.middleatlantic;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.dal.communicator.middleatlantic.select.MiddleAtlanticPowerUnitCommunicator;
import com.google.common.base.Optional;
import org.checkerframework.checker.optional.qual.Present;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;


@Tag("integrationTest")
public class MiddleAtlanticPowerUnitCommunicatorTest {
    static MiddleAtlanticPowerUnitCommunicator middleAtlanticPowerUnitCommunicator;

    @BeforeEach
    public void init() throws Exception {
        middleAtlanticPowerUnitCommunicator = new MiddleAtlanticPowerUnitCommunicator();
        middleAtlanticPowerUnitCommunicator.setHost("***REMOVED***");
        middleAtlanticPowerUnitCommunicator.setPort(60000);
        middleAtlanticPowerUnitCommunicator.setLogin("user");
        middleAtlanticPowerUnitCommunicator.setPassword("12345");
        middleAtlanticPowerUnitCommunicator.init();
    }

    @Test
    public void outletControlTest() throws Exception {
        List<Statistics> initialStatistics = middleAtlanticPowerUnitCommunicator.getMultipleStatistics();
        Thread.sleep(30000);
        initialStatistics = middleAtlanticPowerUnitCommunicator.getMultipleStatistics();
        Thread.sleep(30000);
        initialStatistics = middleAtlanticPowerUnitCommunicator.getMultipleStatistics();

        String outletName = "Outlet 1 - 1";
        String initialOutletValue = ((ExtendedStatistics)initialStatistics.get(0)).getStatistics().get(outletName);

        String targetValue = "0".equals(initialOutletValue) ? "1" : "0";
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Outlet 1 - 1");
        controllableProperty.setValue(targetValue);

        middleAtlanticPowerUnitCommunicator.controlProperty(controllableProperty);

        List<Statistics> updatedStatistics = middleAtlanticPowerUnitCommunicator.getMultipleStatistics();
        Assert.assertEquals(targetValue, ((ExtendedStatistics)updatedStatistics.get(0)).getStatistics().get(controllableProperty.getProperty()));

        controllableProperty.setValue(initialOutletValue);
        middleAtlanticPowerUnitCommunicator.controlProperty(controllableProperty);
    }

    @Test
    public void sequenceOnTest() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Sequence up");
        middleAtlanticPowerUnitCommunicator.controlProperty(controllableProperty);

        Thread.sleep(5000);
        List<Statistics> updatedStatistics = middleAtlanticPowerUnitCommunicator.getMultipleStatistics();
        Assert.assertEquals(0, ((ExtendedStatistics)updatedStatistics.get(0)).getStatistics().values().stream().filter(s -> s.equals("0")).count());
    }

    @Test
    public void sequenceOffTest() throws Exception {
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Sequence down");
        middleAtlanticPowerUnitCommunicator.controlProperty(controllableProperty);

        Thread.sleep(5000);
        List<Statistics> updatedStatistics = middleAtlanticPowerUnitCommunicator.getMultipleStatistics();
        Assert.assertEquals(0, ((ExtendedStatistics)updatedStatistics.get(0)).getStatistics().values().stream().filter(s -> s.equals("1")).count());
    }

    @Test
    public void getMultipleStatisticsTest() throws Exception {
        middleAtlanticPowerUnitCommunicator.init();
        List<Statistics> stats = middleAtlanticPowerUnitCommunicator.getMultipleStatistics();
        Assert.assertNotEquals(0, ((ExtendedStatistics)stats.get(0)).getStatistics().size());
        Assert.assertNotEquals(0, ((ExtendedStatistics)stats.get(0)).getControllableProperties().size());
    }
}
