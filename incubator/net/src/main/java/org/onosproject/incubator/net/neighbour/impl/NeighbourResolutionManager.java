/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.incubator.net.neighbour.impl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.packet.ARP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP6;
import org.onlab.packet.IPv6;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.Ip6Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.VlanId;
import org.onlab.packet.ndp.NeighborAdvertisement;
import org.onlab.packet.ndp.NeighborDiscoveryOptions;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.incubator.net.intf.Interface;
import org.onosproject.incubator.net.neighbour.NeighbourHandlerRegistration;
import org.onosproject.incubator.net.neighbour.NeighbourMessageActions;
import org.onosproject.incubator.net.neighbour.NeighbourMessageContext;
import org.onosproject.incubator.net.neighbour.NeighbourMessageHandler;
import org.onosproject.incubator.net.neighbour.NeighbourResolutionService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.onlab.packet.Ethernet.TYPE_ARP;
import static org.onlab.packet.Ethernet.TYPE_IPV6;
import static org.onlab.packet.ICMP6.NEIGHBOR_ADVERTISEMENT;
import static org.onlab.packet.ICMP6.NEIGHBOR_SOLICITATION;
import static org.onlab.packet.IPv6.PROTOCOL_ICMP6;
import static org.onosproject.net.packet.PacketPriority.CONTROL;

/**
 * Manages handlers for neighbour messages.
 */
@Service
@Component(immediate = true)
public class NeighbourResolutionManager implements NeighbourResolutionService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected EdgePortService edgeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService componentConfigService;

    @Property(name = "ndpEnabled", boolValue = false,
            label = "Enable IPv6 neighbour discovery")
    protected boolean ndpEnabled = false;

    private static final String APP_NAME = "org.onosproject.neighbour";
    private ApplicationId appId;

    private final SetMultimap<ConnectPoint, NeighbourHandlerRegistration> packetHandlers =
            Multimaps.synchronizedSetMultimap(HashMultimap.create());

    private final InternalPacketProcessor processor = new InternalPacketProcessor();
    private final InternalNeighbourMessageActions actions = new InternalNeighbourMessageActions();

    @Activate
    protected void activate(ComponentContext context) {
        appId = coreService.registerApplication(APP_NAME);

        componentConfigService.registerProperties(getClass());
        modified(context);

        packetService.addProcessor(processor, PacketProcessor.director(1));
    }

    @Deactivate
    protected void deactivate() {
        cancelPackets();
        packetService.removeProcessor(processor);
        componentConfigService.unregisterProperties(getClass(), false);
    }

    @Modified
    protected void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        Boolean flag;

        flag = Tools.isPropertyEnabled(properties, "ndpEnabled");
        if (flag != null) {
            ndpEnabled = flag;
            log.info("IPv6 neighbor discovery is {}",
                    ndpEnabled ? "enabled" : "disabled");
        }

        synchronized (packetHandlers) {
            if (!packetHandlers.isEmpty()) {
                requestPackets();
            }
        }
    }

    private void requestPackets() {
        packetService.requestPackets(buildArpSelector(), CONTROL, appId);

        if (ndpEnabled) {
            packetService.requestPackets(buildNeighborSolicitationSelector(),
                    CONTROL, appId);
            packetService.requestPackets(buildNeighborAdvertisementSelector(),
                    CONTROL, appId);
        } else {
            packetService.cancelPackets(buildNeighborSolicitationSelector(),
                    CONTROL, appId);
            packetService.cancelPackets(buildNeighborAdvertisementSelector(),
                    CONTROL, appId);
        }
    }

    private void cancelPackets() {
        packetService.cancelPackets(buildArpSelector(), CONTROL, appId);
        packetService.cancelPackets(buildNeighborSolicitationSelector(),
                CONTROL, appId);
        packetService.cancelPackets(buildNeighborAdvertisementSelector(),
                CONTROL, appId);
    }

    private TrafficSelector buildArpSelector() {
        return DefaultTrafficSelector.builder()
                .matchEthType(TYPE_ARP)
                .build();
    }

    private TrafficSelector buildNeighborSolicitationSelector() {
        return DefaultTrafficSelector.builder()
                .matchEthType(TYPE_IPV6)
                .matchIPProtocol(PROTOCOL_ICMP6)
                .matchIcmpv6Type(NEIGHBOR_SOLICITATION)
                .build();
    }

    private TrafficSelector buildNeighborAdvertisementSelector() {
        return DefaultTrafficSelector.builder()
                .matchEthType(TYPE_IPV6)
                .matchIPProtocol(PROTOCOL_ICMP6)
                .matchIcmpv6Type(NEIGHBOR_ADVERTISEMENT)
                .build();
    }

    @Override
    public void registerNeighbourHandler(ConnectPoint connectPoint,
                                         NeighbourMessageHandler handler,
                                         ApplicationId appId) {
        register(connectPoint, new HandlerRegistration(handler, appId));
    }

    @Override
    public void registerNeighbourHandler(Interface intf,
                                         NeighbourMessageHandler handler,
                                         ApplicationId appId) {
        register(intf.connectPoint(), new HandlerRegistration(handler, intf, appId));
    }

    private void register(ConnectPoint connectPoint, HandlerRegistration registration) {
        synchronized (packetHandlers) {
            if (packetHandlers.isEmpty()) {
                requestPackets();
            }
            packetHandlers.put(connectPoint, registration);
        }
    }

    @Override
    public void unregisterNeighbourHandler(ConnectPoint connectPoint,
                                           NeighbourMessageHandler handler,
                                           ApplicationId appId) {
        unregister(connectPoint, new HandlerRegistration(handler, appId));
    }

    @Override
    public void unregisterNeighbourHandler(Interface intf,
                                           NeighbourMessageHandler handler,
                                           ApplicationId appId) {
        unregister(intf.connectPoint(), new HandlerRegistration(handler, intf, appId));
    }

    private void unregister(ConnectPoint connectPoint, HandlerRegistration registration) {
        synchronized (packetHandlers) {
            packetHandlers.remove(connectPoint, registration);

            if (packetHandlers.isEmpty()) {
                cancelPackets();
            }
        }
    }

    @Override
    public void unregisterNeighbourHandlers(ApplicationId appId) {
        synchronized (packetHandlers) {
            Iterator<NeighbourHandlerRegistration> it = packetHandlers.values().iterator();

            while (it.hasNext()) {
                NeighbourHandlerRegistration registration = it.next();
                if (registration.appId().equals(appId)) {
                    it.remove();
                }
            }

            if (packetHandlers.isEmpty()) {
                cancelPackets();
            }
        }
    }

    @Override
    public Map<ConnectPoint, Collection<NeighbourHandlerRegistration>> getHandlerRegistrations() {
        return ImmutableMap.copyOf(Multimaps.asMap(packetHandlers));
    }

    public void handlePacket(PacketContext context) {
        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();

        NeighbourMessageContext msgContext =
                DefaultNeighbourMessageContext.createContext(ethPkt, pkt.receivedFrom(), actions);

        if (msgContext == null) {
            return;
        }

        handleMessage(msgContext);

        context.block();
    }

    private void handleMessage(NeighbourMessageContext context) {
        Collection<NeighbourHandlerRegistration> handlers = packetHandlers.get(context.inPort());

        handlers.forEach(registration -> {
            if (registration.intf() == null || matches(context, registration.intf())) {
                registration.handler().handleMessage(context, hostService);
            }
        });
    }

    /**
     * Checks that incoming packet matches the parameters of the interface.
     * This means that if the interface specifies a particular parameter
     * (VLAN, IP address, etc.) then the incoming packet should match those
     * parameters.
     *
     * @param context incoming message context
     * @param intf interface to check
     * @return true if the incoming message matches the interface, otherwise false
     */
    private boolean matches(NeighbourMessageContext context, Interface intf) {
        checkNotNull(context);
        checkNotNull(intf);

        boolean matches = true;
        // For non-broadcast packets, if the interface has a MAC address check that
        // the destination MAC address of the packet matches the interface MAC
        if (!context.dstMac().isBroadcast() &&
                !intf.mac().equals(MacAddress.NONE) &&
                !intf.mac().equals(context.dstMac())) {
            matches = false;
        }
        // If the interface has a VLAN, check that the packet's VLAN matches
        if (!intf.vlan().equals(VlanId.NONE) && !intf.vlan().equals(context.vlan())) {
            matches = false;
        }
        // If the interface has IP addresses, check that the packet's target IP
        // address matches one of the interface IP addresses
        if (!intf.ipAddressesList().isEmpty() && !hasIp(intf, context.target())) {
            matches = false;
        }

        return matches;
    }

    /**
     * Returns true if the interface has the given IP address.
     *
     * @param intf interface to check
     * @param ip IP address
     * @return true if the IP is configured on the interface, otherwise false
     */
    private boolean hasIp(Interface intf, IpAddress ip) {
        return intf.ipAddressesList().stream()
                .anyMatch(intfAddress -> intfAddress.ipAddress().equals(ip));
    }

    private void reply(NeighbourMessageContext context, MacAddress targetMac) {
        switch (context.protocol()) {
        case ARP:
            sendTo(ARP.buildArpReply((Ip4Address) context.target(),
                    targetMac, context.packet()), context.inPort());
            break;
        case NDP:
            sendTo(buildNdpReply((Ip6Address) context.target(), targetMac,
                    context.packet()), context.inPort());
            break;
        default:
            break;
        }
    }

    /**
     * Outputs a packet out a specific port.
     *
     * @param packet  the packet to send
     * @param outPort the port to send it out
     */
    private void sendTo(Ethernet packet, ConnectPoint outPort) {
        sendTo(ByteBuffer.wrap(packet.serialize()), outPort);
    }

    /**
     * Outputs a packet out a specific port.
     *
     * @param packet packet to send
     * @param outPort port to send it out
     */
    private void sendTo(ByteBuffer packet, ConnectPoint outPort) {
        if (!edgeService.isEdgePoint(outPort)) {
            // Sanity check to make sure we don't send the packet out an
            // internal port and create a loop (could happen due to
            // misconfiguration).
            return;
        }

        TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
        builder.setOutput(outPort.port());
        packetService.emit(new DefaultOutboundPacket(outPort.deviceId(),
                builder.build(), packet));
    }

    /**
     * Builds an NDP reply based on a request.
     *
     * @param srcIp   the IP address to use as the reply source
     * @param srcMac  the MAC address to use as the reply source
     * @param request the Neighbor Solicitation request we got
     * @return an Ethernet frame containing the Neighbor Advertisement reply
     */
    private Ethernet buildNdpReply(Ip6Address srcIp, MacAddress srcMac,
                                   Ethernet request) {
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(request.getSourceMAC());
        eth.setSourceMACAddress(srcMac);
        eth.setEtherType(Ethernet.TYPE_IPV6);
        eth.setVlanID(request.getVlanID());

        IPv6 requestIp = (IPv6) request.getPayload();
        IPv6 ipv6 = new IPv6();
        ipv6.setSourceAddress(srcIp.toOctets());
        ipv6.setDestinationAddress(requestIp.getSourceAddress());
        ipv6.setHopLimit((byte) 255);

        ICMP6 icmp6 = new ICMP6();
        icmp6.setIcmpType(ICMP6.NEIGHBOR_ADVERTISEMENT);
        icmp6.setIcmpCode((byte) 0);

        NeighborAdvertisement nadv = new NeighborAdvertisement();
        nadv.setTargetAddress(srcIp.toOctets());
        nadv.setSolicitedFlag((byte) 1);
        nadv.setOverrideFlag((byte) 1);
        nadv.addOption(NeighborDiscoveryOptions.TYPE_TARGET_LL_ADDRESS,
                srcMac.toBytes());

        icmp6.setPayload(nadv);
        ipv6.setPayload(icmp6);
        eth.setPayload(ipv6);
        return eth;
    }

    /**
     * Stores a neighbour message handler registration.
     */
    private class HandlerRegistration implements NeighbourHandlerRegistration {
        private final Interface intf;
        private final NeighbourMessageHandler handler;
        private final ApplicationId appId;

        /**
         * Creates a new handler registration.
         *
         * @param handler neighbour message handler
         */
        public HandlerRegistration(NeighbourMessageHandler handler, ApplicationId appId) {
            this(handler, null, appId);
        }

        /**
         * Creates a new handler registration.
         *
         * @param handler neighbour message handler
         * @param intf interface
         */
        public HandlerRegistration(NeighbourMessageHandler handler, Interface intf, ApplicationId appId) {
            this.intf = intf;
            this.handler = handler;
            this.appId = appId;
        }

        @Override
        public Interface intf() {
            return intf;
        }

        @Override
        public NeighbourMessageHandler handler() {
            return handler;
        }

        @Override
        public ApplicationId appId() {
            return appId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof HandlerRegistration)) {
                return false;
            }

            HandlerRegistration that = (HandlerRegistration) other;

            return Objects.equals(intf, that.intf) &&
                    Objects.equals(handler, that.handler) &&
                    Objects.equals(appId, that.appId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(intf, handler, appId);
        }
    }

    /**
     * Packet processor for incoming packets.
     */
    private class InternalPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            // Stop processing if the packet has been handled, since we
            // can't do any more to it.
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            if (ethPkt == null) {
                return;
            }

            if (ethPkt.getEtherType() == TYPE_ARP) {
                // handle ARP packets
                handlePacket(context);
            } else if (ethPkt.getEtherType() == TYPE_IPV6) {
                IPv6 ipv6 = (IPv6) ethPkt.getPayload();
                if (ipv6.getNextHeader() == IPv6.PROTOCOL_ICMP6) {
                    ICMP6 icmp6 = (ICMP6) ipv6.getPayload();
                    if (icmp6.getIcmpType() == NEIGHBOR_SOLICITATION ||
                            icmp6.getIcmpType() == NEIGHBOR_ADVERTISEMENT) {
                        // handle ICMPv6 solicitations and advertisements (NDP)
                        handlePacket(context);
                    }
                }
            }
        }
    }

    private class InternalNeighbourMessageActions implements NeighbourMessageActions {

        @Override
        public void reply(NeighbourMessageContext context, MacAddress targetMac) {
            NeighbourResolutionManager.this.reply(context, targetMac);
        }

        @Override
        public void forward(NeighbourMessageContext context, ConnectPoint outPort) {
            sendTo(context.packet(), outPort);
        }

        @Override
        public void forward(NeighbourMessageContext context, Interface outIntf) {
            // TODO implement
        }

        @Override
        public void flood(NeighbourMessageContext context) {
            edgeService.getEdgePoints().forEach(connectPoint -> {
                if (!connectPoint.equals(context.inPort())) {
                    sendTo(context.packet(), connectPoint);
                }
            });
        }

        @Override
        public void drop(NeighbourMessageContext context) {

        }
    }

}
