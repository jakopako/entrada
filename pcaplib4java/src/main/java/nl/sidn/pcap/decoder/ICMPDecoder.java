/*
 * ENTRADA, a big data platform for network data analytics
 *
 * Copyright (C) 2016 SIDN [https://www.sidn.nl]
 * 
 * This file is part of ENTRADA.
 * 
 * ENTRADA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ENTRADA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with ENTRADA.  If not, see [<http://www.gnu.org/licenses/].
 *
 */	
package nl.sidn.pcap.decoder;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import nl.sidn.pcap.PcapReader;
import nl.sidn.pcap.PcapReaderUtil;
import nl.sidn.pcap.packet.DNSPacket;
import nl.sidn.pcap.packet.ICMPPacket;
import nl.sidn.pcap.packet.Packet;
import nl.sidn.pcap.util.ICMPv4Util;
import nl.sidn.pcap.util.ICMPv6Util;

/**
 * Decode the ICMP payload of an IP packet
 *
 */
public class ICMPDecoder {
	
	public static final Log LOG = LogFactory.getLog(ICMPDecoder.class);
	
	public static final int PROTOCOL_ICMP_V4 = 1;
	public static final int PROTOCOL_ICMP_V6 = 58;
	public static final int PROTOCOL_ICMP_V6_OFFSET_INFO_MSG = 128;
	
	//echo request, used to determine client info
	public static final int PROTOCOL_ICMP_V4_ECHO_REQUEST = 8;
	public static final int PROTOCOL_ICMP_V4_ECHO_REPLY = 0;
	public static final int PROTOCOL_ICMP_V6_ECHO_REQUEST = 128;
	public static final int PROTOCOL_ICMP_V6_ECHO_REPLY = 129;
	
	//error types with ip/tcp/udp payload
	public static final int PROTOCOL_ICMP_DESTINATION_UNREACHABLE = 3;
	public static final int PROTOCOL_ICMP_SOURCE_QUENCHE = 4;
	public static final int PROTOCOL_ICMP_REDIRECT_MESSAGE = 5;
	public static final int PROTOCOL_ICMP_TIME_EXCEEDED = 11;
	public static final int PROTOCOL_ICMP_PARAMETER_PROBLEM = 12;
	
	//values to identify clients sending pings
	//http://atlas.ripe.net might be truncated due to user option to set size, use the smallest string to detect 
	public static final String ECHO_CLIENT_ID_RIPE_ATLAS = "http://atlas.ripe.net";
	public static final String ECHO_CLIENT_ID_UNIX_LINUX = "!\"#$%&'()*+,-./01234567";
	public static final String ECHO_CLIENT_ID_WINDOWS = "abcdefghijklmnopqrstuvwabcdefghi";
	public static final String ECHO_CLIENT_ID_PRTG = "P I N G   b y   P R T G ";
	//types of ping clients
	public static final int ECHO_CLIENT_TYPE_UNKNOWN = 0;
	public static final int ECHO_CLIENT_TYPE_RIPE_ATLAS = 1;
	public static final int ECHO_CLIENT_TYPE_UNIX_LINUX = 2;
	public static final int ECHO_CLIENT_TYPE_WINDOWS = 3;
	public static final int ECHO_CLIENT_TYPE_PRTG = 4;
	
	private IPDecoder ipDecoder = new IPDecoder();
	private UDPDecoder udpDecoder = new UDPDecoder();
	private TCPDecoder tcpDecoder = new TCPDecoder();
	private DNSDecoder dnsDecoder = new DNSDecoder();
	
	public void reassemble(ICMPPacket packet, int ipStart,  byte[] packetData){
		int payloadDataStart = ipStart + packet.getIpHeaderLen();
		int payloadLength = packetData.length - packet.getIpHeaderLen();
		byte[] payload = PcapReaderUtil.readPayload(packetData, payloadDataStart, payloadLength);
		decode(packet, payload);
	}
	
	public void decode(ICMPPacket packet, byte[] packetData){
		
		if(packet.getIpVersion() == IPDecoder.IP_PROTOCOL_VERSION_4){
			//ICMPv4
			//decode hdr
			packet.setType(ICMPv4Util.decodeType(packetData));
			packet.setCode(ICMPv4Util.decodeCode(packetData));
			
			//get the last 4 bytes of the header, this contains info 
			//for specific types, ignore this data for now.
			//byte[] restOfHdr = ICMPv4Util.extractRestOfHeader(packetData);
			//decode the icmp payload which is IP hdr and 8 bytes of IP payload
			//get layer 4 payload
			//only these ipv4 error types contain a payload with the original packet
			if(packet.getType() == PROTOCOL_ICMP_DESTINATION_UNREACHABLE ||
					packet.getType() == PROTOCOL_ICMP_SOURCE_QUENCHE ||
					packet.getType() == PROTOCOL_ICMP_REDIRECT_MESSAGE ||
					packet.getType() == PROTOCOL_ICMP_TIME_EXCEEDED ||
					packet.getType() == PROTOCOL_ICMP_PARAMETER_PROBLEM){
			
				decodePayload(packet, ICMPv4Util.extractPayload(packetData));
				packet.setError(true);
			}else{
				packet.setOriginalIPPacket(Packet.NULL);
				packet.setInfo(true);
			}
			
		}else{
			//ICMPv6
			//decode hdr
			packet.setType(ICMPv6Util.decodeType(packetData));
			packet.setCode(ICMPv6Util.decodeCode(packetData));
			//skip the checksum and 16-32 bit range
			if(packet.getType() < PROTOCOL_ICMP_V6_OFFSET_INFO_MSG){
				//only icmpv6 error messages ( type <128) contain original packet
				decodePayload(packet, ICMPv6Util.extractPayload(packetData));
				packet.setError(true);
			}else{
				packet.setOriginalIPPacket(Packet.NULL);
				packet.setInfo(true);
			}
		}
		
		//if icmp type is echo request then see if it is a RIPE probe
		if( packet.getType() == PROTOCOL_ICMP_V4_ECHO_REQUEST ||
			packet.getType() == PROTOCOL_ICMP_V4_ECHO_REPLY ||
			packet.getType() == PROTOCOL_ICMP_V6_ECHO_REQUEST ||
			packet.getType() == PROTOCOL_ICMP_V6_ECHO_REPLY){
			
			if(isClientRipeAtlas(packetData)){
				packet.setClientType(ECHO_CLIENT_TYPE_RIPE_ATLAS);
			}else if(isClientUnixLinux(packetData)){
				packet.setClientType(ECHO_CLIENT_TYPE_UNIX_LINUX);
			}else if(isClientWindows(packetData)){
				packet.setClientType(ECHO_CLIENT_TYPE_WINDOWS);
			}else if(isClientPrtg(packetData)){
				packet.setClientType(ECHO_CLIENT_TYPE_PRTG);
			}
			else if(LOG.isDebugEnabled()){
				byte[] echoBytes = ICMPv4Util.extractEchoRequestPayload(packetData);
				String echoStr = new String(echoBytes);
				LOG.debug(packet.getSrc() + ": " + echoStr);
			}
		}
		
	}
	
	private void decodePayload(ICMPPacket packet, byte[] icmpPayload){

		Packet ipPacket = ipDecoder.decode(icmpPayload, 0);
		packet.setOriginalIPPacket(ipPacket);
		if(ipPacket == Packet.NULL){
			//decode failed
			return;
		}

		if(ipPacket instanceof DNSPacket){
			DNSPacket dnsPacket = (DNSPacket)ipPacket;
			byte[] layer4Payload = null;
			if(PcapReader.PROTOCOL_UDP == ipPacket.getProtocol()){
				//reassemble(Packet packet, int ipHeaderLen,int totalLength, int ipStart, byte[] packetData)
				layer4Payload = udpDecoder.reassemble(ipPacket, ipPacket.getIpHeaderLen(), icmpPayload.length, 0,icmpPayload);
			}else if ( PcapReader.PROTOCOL_TCP == ipPacket.getProtocol()) {
				layer4Payload = tcpDecoder.decode(ipPacket, icmpPayload, 0, ipPacket.getIpHeaderLen(), icmpPayload.length);
			}
			
			if(layer4Payload != null && layer4Payload.length > 0){
				dnsDecoder.decode(dnsPacket, layer4Payload, true);
				//set the correct size of the message
				if( dnsPacket.getMessageCount() > 0){
					//this is the number of bytes in the ICMP copy
					//this could be truncated to 8 bytes, so get the original dns response size
					dnsPacket.getMessages().get(0).setBytes(ipPacket.getPayloadLength());
				}
			}
		}else if(ipPacket instanceof ICMPPacket){
			//get the icmp payload
			int payloadDataStart = ipPacket.getIpHeaderLen();
			int payloadLength = icmpPayload.length - payloadDataStart;
			//get ip payload, which is icmp packet
			byte[] payload = PcapReaderUtil.readPayload(icmpPayload, payloadDataStart, payloadLength);
			decode( (ICMPPacket)ipPacket,payload);
		}
		
		
	}
	
	private boolean isClientRipeAtlas(byte[] packetData){
		//return isClient(packetData,ECHO_CLIENT_ID_RIPE_ATLAS);
		byte[] echoBytes = ICMPv4Util.extractEchoRequestPayload(packetData);
		String echoStr = new String(echoBytes);
		
		//atlas probes have "http://atlas.ripe.net" in their echo bytes.
		//some probe type only have a substring of this url in the payload
		return StringUtils.contains(echoStr, ECHO_CLIENT_ID_RIPE_ATLAS); 

	}
	
	private boolean isClientUnixLinux(byte[] packetData){
		return isClient(packetData,ECHO_CLIENT_ID_UNIX_LINUX);
	}
	
	private boolean isClientWindows(byte[] packetData){
		return isClient(packetData,ECHO_CLIENT_ID_WINDOWS);
	}
	private boolean isClientPrtg(byte[] packetData){
		return isClient(packetData,ECHO_CLIENT_ID_PRTG);
	}
	
	private boolean isClient(byte[] packetData, String id){
		byte[] echoBytes = ICMPv4Util.extractEchoRequestPayload(packetData);
		String echoStr = new String(echoBytes);
		return StringUtils.contains(echoStr,id);
	}
	

}
