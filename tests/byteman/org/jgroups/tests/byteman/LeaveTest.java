package org.jgroups.tests.byteman;

import org.jgroups.Global;
import org.jgroups.JChannel;
import org.jgroups.protocols.*;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.protocols.pbcast.STABLE;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.InetAddress;
import java.util.stream.Stream;

/**
 * Tests graceful leaves of multiple members, especially coord and next-in-line.
 * Nodes are leaving gracefully so no merging is expected.<br/
 * Reproducer for https://issues.jboss.org/browse/JGRP-2293.
 *
 * @author Radoslav Husar
 * @author Bela Ban
 */
@Test(groups = Global.FUNCTIONAL, singleThreaded = true)
public class LeaveTest {

    protected static final int NUM = 4;
    protected static final int NUM_LEAVERS = 2;
    protected static final InetAddress LOOPBACK;

    static {
        try {
            LOOPBACK = Util.getLocalhost();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected JChannel[] channels = new JChannel[NUM];

    @BeforeMethod
    protected void setup() throws Exception {
        for (int i = 0; i < channels.length; i++) {
            channels[i] = create(String.valueOf(i + 1)).connect(LeaveTest.class.getSimpleName());
            Util.sleep(i < 1 ? 2000 : 100);
        }
        Util.waitUntilAllChannelsHaveSameView(10000, 1000, channels);
    }

    @AfterMethod
    protected void destroy() {
        Util.closeReverse(channels);
    }

    /** A single member (coord) leaves */
    public void testLeaveOfSingleCoord() throws Exception {
        JChannel x=null;
        destroy();
        try {
            x=create("X").connect("x-cluster");
            assert x.getView().size() == 1;
            Util.close(x);
            assert x.getView() == null;
        }
        finally {
            Util.close(x);
        }
    }

    /** The coord leaves */
    public void testCoordLeave() {
        Util.close(channels[0]);
        Stream.of(1,2,3).map(i -> channels[i])
          .peek(ch -> System.out.printf("%s: %s\n", ch.getAddress(), ch.getView()))
          .allMatch(ch -> ch.getView().size() == 3 && ch.getView().getCoord().equals(channels[1].getAddress()));
    }

    /** A participant leaves */
    public void testParticipantLeave() {
        Util.close(channels[2]);
        Stream.of(0,1,3).map(i -> channels[i])
          .peek(ch -> System.out.printf("%s: %s\n", ch.getAddress(), ch.getView()))
          .allMatch(ch -> ch.getView().size() == 3 && ch.getView().getCoord().equals(channels[0].getAddress()));
    }

    public void testConcurrentLeaves() throws Exception {
        System.out.println(Util.printViews(channels));
        System.out.println("\n");

        JChannel[] remaining_channels = new JChannel[channels.length - NUM_LEAVERS];
        System.arraycopy(channels, NUM_LEAVERS, remaining_channels, 0, channels.length - NUM_LEAVERS);

        Stream.of(channels).limit(NUM_LEAVERS).parallel().forEach(Util::close);
        Util.waitUntilAllChannelsHaveSameView(30000, 1000, remaining_channels);

        System.out.println(Util.printViews(remaining_channels));
    }

    protected static JChannel create(String name) throws Exception {
        return new JChannel(
                new TCP().setBindAddress(LOOPBACK),
                new MPING(),
                // omit MERGE3 from the stack -- nodes are leaving gracefully
                // new MERGE3().setMinInterval(1000).setMaxInterval(3000).setCheckInterval(5000),
                new FD_SOCK(),
                new FD_ALL(),
                new VERIFY_SUSPECT(),
                new NAKACK2().setUseMcastXmit(false),
                new UNICAST3(),
                new STABLE(),
                new GMS().joinTimeout(1000))
                .name(name);
    }
}
