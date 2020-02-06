package niogroupchat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;

public class ChatServer {

    private int port = 2333;   // 默认端口
    private ServerSocketChannel serverSocketChannel;
    private Selector selector;
    private int baseBufferSize = 1024;   // 默认buffer大小
    private ByteBuffer oriAddr;   // 用于转发信息来源地址
    private ByteBuffer msgBuffer;


    public ChatServer(int port){
        this.port = port;
        this.initEnv();
    }

    // 初始化服务器
    public void initEnv(){
        try {
            // 1. 取得一个serversocketchannel对象
            this.serverSocketChannel = ServerSocketChannel.open();
            // 2. 取得一个selector对象
            this.selector = Selector.open();
            // 3. 创建一个serversocket，并监听2333端口
            this.serverSocketChannel.socket().bind(new InetSocketAddress(this.port));
            // 4. 设置非阻塞方式
            this.serverSocketChannel.configureBlocking(false);
            // 5. 将serversocketchannel对象注册到selector，也就是将服务端绑定selector
            this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
            // 6. 初始化原地址buffer
            this.oriAddr = ByteBuffer.allocate(256);
            this.msgBuffer = ByteBuffer.allocate(this.baseBufferSize);
            // 输出初始化成功信息
            System.out.println("init server success, the port is:"+ this.port);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    // 开始监听所有请求
    public void listen(){
        try{
            // 循环遍历是否有事件
            while (true){
                // 阻塞0.1s看是否有客户端连接，防止CPU全速空转
                if(this.selector.select(100)==0){
                    continue;
                }
                // 取得selectionkey，判断通道里的事件
                Iterator<SelectionKey> iterator = this.selector.selectedKeys().iterator();
                while (iterator.hasNext()){
                    SelectionKey selectedKey = iterator.next();
                    if(selectedKey.isAcceptable()){
                        // 如果是连接事件，则给客户端生成一个channel
                        SocketChannel socketChannel = this.serverSocketChannel.accept();
                        socketChannel.configureBlocking(false);
                        // 向selector注册
                        socketChannel.register(this.selector, SelectionKey.OP_READ);
                        // 已经登录完成，接下来调用登录处理逻辑
                        this.login(socketChannel);
                    }else if(selectedKey.isReadable()){
                        readData(selectedKey);
                    }
                    iterator.remove();
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 接收到登陆请求时的处理逻辑
     * @param socketChannel
     * @throws IOException
     */
    public void login(SocketChannel socketChannel) throws IOException {
        System.out.println(socketChannel.getRemoteAddress().toString() + "上线了");
        // 将上限消息发送给所有客户端，加锁防止同时出现有客户端登陆和退出的情况。但我这里只有一个线程处理消息所以不是必须的
        synchronized (oriAddr){
            oriAddr.clear();
            byte[] loginBytes = ("server: " + socketChannel.getRemoteAddress().toString() + "上线了").getBytes();
            int remain = loginBytes.length % 4;
            if(remain>0){
                loginBytes = java.util.Arrays.copyOf(loginBytes, loginBytes.length+4-remain);
            }
            oriAddr.putInt(loginBytes.length);
            oriAddr.put(loginBytes);
            sendData2All(socketChannel, oriAddr);
        }
    }

    /**
     * 接收到退出请求时的处理逻辑
     * @param socketChannel
     * @throws IOException
     */
    public void logout(SocketChannel socketChannel) throws IOException {
        System.out.println(socketChannel.getRemoteAddress().toString() + "下线了");
        // 将上限消息发送给所有客户端，加锁防止同时出现有客户端登陆和退出的情况。但我这里只有一个线程处理消息所以不是必须的
        synchronized (oriAddr){
            oriAddr.clear();
            byte[] logoutBytes = ("server: " + socketChannel.getRemoteAddress().toString() + "下线了").getBytes();
            int remain = logoutBytes.length % 4;
            if(remain>0){
                logoutBytes = java.util.Arrays.copyOf(logoutBytes, logoutBytes.length+4-remain);
            }
            oriAddr.putInt(logoutBytes.length);
            oriAddr.put(logoutBytes);
            sendData2All(socketChannel, oriAddr);
        }
        socketChannel.close();
    }

    /**
     * 读取消息逻辑
     * @param selectionKey
     */
    private void readData(SelectionKey selectionKey){
        try{
            // 通过key反向得到channel
            SocketChannel socketChannel = (SocketChannel)selectionKey.channel();
            // 创建读取数据的缓冲区
            ByteBuffer byteBuffer = this.msgBuffer;
            // 首先清空buffer
            byteBuffer.clear();
            // 读取数据，并记录数据大小
            int count = socketChannel.read(byteBuffer);
            if(count>0){
                System.out.println("get msg: from"+socketChannel.getRemoteAddress().toString());
                // 防止消息信息太大出现需要多次读取
                while (count>0){
                    sendData2All(socketChannel, byteBuffer);
                    byteBuffer.clear();
                    count = socketChannel.read(byteBuffer);
                }
            }else if(count==-1){
                this.logout((SocketChannel)selectionKey.channel());
            }
        }catch (Exception e){
            try {
                this.logout((SocketChannel)selectionKey.channel());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

    }

    /**
     * 向所有客户端发送消息，由于是中间方法，所以异常抛出
     * @param socketChannel
     * @param msg
     * @throws IOException
     */
    private void sendData2All(SocketChannel socketChannel, ByteBuffer msg) throws IOException {
        msg.flip();
        Iterator<SelectionKey> iterator = this.selector.keys().iterator();
        while (iterator.hasNext()){
            SelectionKey key = iterator.next();
            SelectableChannel targetChannel = key.channel();
            if((targetChannel instanceof SocketChannel) && !targetChannel.equals(socketChannel)){
                // 直接通过buffer将数据转发到其它客户端
                ((SocketChannel)targetChannel).write(msg);
                // 重置position，以方便下次使用buffer
                msg.rewind();
            }
        }
    }

    /**
     * 向单一客户端发送消息
     * @param targetChannel
     * @param msg
     * @throws IOException
     */
    private void sendData2One(SocketChannel targetChannel, ByteBuffer msg) throws IOException {
        if(targetChannel instanceof SocketChannel){
            ((SocketChannel)targetChannel).write(msg);
        }
    }



    public static void main(String[] args) {
        final int PORT = 2333;
        ChatServer chatServer = new ChatServer(2333);
        chatServer.listen();
    }






}
