/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.gateway.handlers.detecting.protocol.mqtt;

import org.fusesource.gateway.handlers.detecting.Protocol;
import org.fusesource.gateway.loadbalancer.ConnectionParameters;
import org.fusesource.hawtbuf.UTF8Buffer;
import org.fusesource.mqtt.codec.CONNECT;
import org.fusesource.mqtt.codec.MQTTFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.net.NetSocket;

import static org.fusesource.gateway.handlers.detecting.protocol.BufferSupport.*;

/**
 * Implements protocol decoding for the MQTT protocol.
 */
public class MqttProtocol implements Protocol {
    private static final transient Logger LOG = LoggerFactory.getLogger(MqttProtocol.class);

    static final Buffer HEAD_MAGIC = new Buffer(new byte []{ 0x10 });
    static final Buffer MQTT31_TAIL_MAGIC = new Buffer(new byte []{ 0x00, 0x06, 'M', 'Q', 'I', 's', 'd', 'p'});
    static final Buffer MQTT311_TAIL_MAGIC = new Buffer(new byte []{ 0x00, 0x04, 'M', 'Q', 'T', 'T'});

    int maxMessageLength = 1024*1024*100;

    @Override
    public String getProtocolName() {
        return "mqtt";
    }

    public int getMaxIdentificationLength() {
        return 13;
    }

    @Override
    public boolean matches(Buffer header) {
        if (header.length() < 10) {
          return false;
        } else {
          return startsWith(header, HEAD_MAGIC) && (
              indexOf(header, 2, MQTT31_TAIL_MAGIC)< 6
              ||
              indexOf(header, 2, MQTT311_TAIL_MAGIC) < 6
          );
        }
    }

    static void append(Buffer self, MQTTFrame value) {
        MQTTFrame frame = (MQTTFrame) value;
        self.appendByte(frame.header());

        int remaining = 0;
        for(org.fusesource.hawtbuf.Buffer buffer : frame.buffers) {
            remaining += buffer.length;
        }
        do {
            byte digit = (byte) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining > 0) {
                digit |= 0x80;
            }
            self.appendByte(digit);
        } while (remaining > 0);
        for(org.fusesource.hawtbuf.Buffer buffer : frame.buffers) {
            // TODO: see if we avoid the byte[] conversion.
            self.appendBytes(buffer.toByteArray());
        }
    }

    @Override
    public void snoopConnectionParameters(final NetSocket socket, final Buffer received, final Handler<ConnectionParameters> handler) {

        final MqttProtocolDecoder h = new MqttProtocolDecoder(this);
        h.errorHandler(new Handler<String>() {
            @Override
            public void handle(String error) {
                LOG.info("STOMP protocol decoding error: "+error);
                socket.close();
            }
        });
        h.codecHandler(new Handler<MQTTFrame>() {
            @Override
            public void handle(MQTTFrame event) {
                try {
                    if (event.messageType() == org.fusesource.mqtt.codec.CONNECT.TYPE) {
                        CONNECT connect = new CONNECT().decode(event);
                        ConnectionParameters parameters = new ConnectionParameters();
                        parameters.protocol = getProtocolName();
                        if( connect.clientId()!=null ) {
                            parameters.protocolClientId = connect.clientId().toString();
                        }
                        if( connect.userName()!=null ) {
                            parameters.protocolUser = connect.userName().toString();

                            // If the user name has a '/' in it, then interpret it as
                            // containing the virtual host info.
                            if( parameters.protocolUser.contains("/") ) {

                                // Strip off the virtual host part of the username..
                                String[] parts = parameters.protocolUser.split("/", 2);

                                parameters.protocolVirtualHost = parts[0];
                                parameters.protocolUser = parts[1];

                                // Update the connect frame to strip out the virtual host from the username field...
                                connect.userName(new UTF8Buffer(parameters.protocolUser));

                                // re-write the received buffer /w  the updated connect frame
                                Buffer tail = received.getBuffer((int) h.getBytesDecoded(), received.length());
                                setLength(received, 0);
                                append(received, connect.encode());
                                received.appendBuffer(tail);
                            }
                        }
                        handler.handle(parameters);
                    } else {
                        LOG.info("Expected a CONNECT frame");
                        socket.close();
                    }
                } catch (java.net.ProtocolException e) {
                    LOG.info("Invalid MQTT frame: " + e, e);
                    socket.close();
                }
            }
        });
        socket.dataHandler(h);
        h.handle(received);
    }

}
