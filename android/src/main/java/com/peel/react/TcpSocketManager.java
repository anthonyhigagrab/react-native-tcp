package com.peel.react;

import android.support.annotation.Nullable;
import android.util.SparseArray;

import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.AsyncServerSocket;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.AsyncSSLSocket;
import com.koushikdutta.async.AsyncSSLSocketWrapper;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.ConnectCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.callback.ListenCallback;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import javax.net.ssl.*;
import javax.net.ssl.SSLEngineResult.*;
import java.io.*;
import java.security.*;
import java.nio.*;


/**
 * Created by aprock on 12/29/15.
 */
public final class TcpSocketManager {
    private SparseArray<Object> mClients = new SparseArray<Object>();

    private WeakReference<TcpSocketListener> mListener;
    private AsyncServer mServer = AsyncServer.getDefault();

    private static String SSL = "SSL";

    private int mInstances = 5000;

    private SSLContext context;

    public TcpSocketManager(TcpSocketListener listener) throws IOException {
        mListener = new WeakReference<TcpSocketListener>(listener);
        try {
            context = SSLContext.getDefault();
        } catch (Exception ex) {
        }
    }

    private void setSocketCallbacks(final Integer cId, final AsyncSocket socket) {
        socket.setClosedCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onClose(cId, ex==null?null:ex.getMessage());
                }
            }
        });

        socket.setDataCallback(new DataCallback() {
            @Override
            public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onData(cId, bb.getAllByteArray());
                }
            }
        });

        socket.setEndCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                if (ex != null) {
                    TcpSocketListener listener = mListener.get();
                    if (listener != null) {
                        listener.onError(cId, ex.getMessage());
                    }
                }
                socket.close();
            }
        });
    }

    public void listen(final Integer cId, final String host, final Integer port) throws UnknownHostException, IOException {
        // resolve the address
        final InetSocketAddress socketAddress;
        if (host != null) {
            socketAddress = new InetSocketAddress(InetAddress.getByName(host), port);
        } else {
            socketAddress = new InetSocketAddress(port);
        }

        mServer.listen(InetAddress.getByName(host), port, new ListenCallback() {
            @Override
            public void onListening(AsyncServerSocket socket) {
                mClients.put(cId, socket);

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onConnect(cId, socketAddress);
                }
            }

            @Override
            public void onAccepted(AsyncSocket socket) {
                setSocketCallbacks(mInstances, socket);
                mClients.put(mInstances, socket);

                AsyncNetworkSocket socketConverted = Util.getWrappedSocket(socket, AsyncNetworkSocket.class);
                InetSocketAddress remoteAddress = socketConverted != null ? socketConverted.getRemoteAddress() : socketAddress;

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onConnection(cId, mInstances, remoteAddress);
                }

                mInstances++;
            }

            @Override
            public void onCompleted(Exception ex) {
                mClients.delete(cId);

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onClose(cId, ex != null ? ex.getMessage() : null);
                }
            }
        });
    }

    private void connectRAW(final AsyncSocket socket, final InetSocketAddress socketAddress, final Integer cId, final @Nullable String host, final Integer port) {
        final TcpSocketListener listener = mListener.get();
        mClients.put(cId, socket);
        setSocketCallbacks(cId, socket);

        if (listener != null) {
            listener.onConnect(cId, socketAddress);
        }
    }

    private void connectSSL(final AsyncSocket socket, final InetSocketAddress socketAddress, final Integer cId, final @Nullable String host, final Integer port) {
        final SSLEngine engine = context.createSSLEngine();
        AsyncSSLSocketWrapper.handshake(socket, host, port, engine, null, null, true, new AsyncSSLSocketWrapper.HandshakeCallback() {
            @Override
            public void onHandshakeCompleted(Exception e, final AsyncSSLSocket socket) {
                final TcpSocketListener listener = mListener.get();
                if (e != null) {
                    if (listener != null) {
                        listener.onError(cId, e.getMessage());
                    }
                    return;
                } 

                // There appears to be a bug in AsyncSSLSocketWrapper where it deletes the Closed callback right after calling
                // onHandshakeCompleted, making it impossible to set the Closed callback in process here. So we cheat and
                // add an intentional post thread to handle it.
                socket.getServer().post(new Runnable() {
                    @Override
                    public void run() {
                        mClients.put(cId, socket);
                        setSocketCallbacks(cId, socket);

                        if (listener != null) {
                            listener.onConnect(cId, socketAddress);
                        }
                    }
                });
            }
        });
    }

    public void connect(final Integer cId, final @Nullable String host, final Integer port, final @Nullable String protocol) throws UnknownHostException, IOException {
        // resolve the address
        final InetSocketAddress socketAddress;
        if (host != null) {
            socketAddress = new InetSocketAddress(InetAddress.getByName(host), port);
        } else {
            socketAddress = new InetSocketAddress(port);
        }

        mServer.connectSocket(socketAddress, new ConnectCallback() {
            @Override
            public void onConnectCompleted(Exception ex, AsyncSocket socket) {
              if (ex != null) {
                  final TcpSocketListener listener = mListener.get();
                  if (listener != null) {
                      listener.onError(cId, ex.getMessage());
                  }
                  return;
              }

              if (protocol != null && SSL.equalsIgnoreCase(protocol)) {
                  connectSSL(socket, socketAddress, cId, host, port);
              } else {
                  connectRAW(socket, socketAddress, cId, host, port);
              }
            }
        });
    }

    public void write(final Integer cId, final byte[] data) {
        Object socket = mClients.get(cId);
        if (socket != null && socket instanceof AsyncSocket) {
            ((AsyncSocket) socket).write(new ByteBufferList(data));
        }
    }

    public void close(final Integer cId) {
        Object socket = mClients.get(cId);
        if (socket != null) {
            if (socket instanceof AsyncSocket) {
                ((AsyncSocket) socket).close();
            } else if (socket instanceof AsyncServerSocket) {
                ((AsyncServerSocket) socket).stop();
            }
        } else {
            TcpSocketListener listener = mListener.get();
            if (listener != null) {
               listener.onError(cId, "unable to find socket");
            }
        }
    }

    public void closeAllSockets() {
        for (int i = 0; i < mClients.size(); i++) {
            close(mClients.keyAt(i));
        }
        mClients.clear();
    }
}
