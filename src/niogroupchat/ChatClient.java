package niogroupchat;

// todo 设置定时器，解决如果长时间没有接收完毕，超时放弃的问题

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Scanner;

public class ChatClient {
    private String host = "127.0.0.1";
    private int port = 2333;
    private int baseBufferSize = 1024;
    private int msgId = 1;
    private Selector selector;
    private SocketChannel socketChannel;
    private HashMap<String, String> usernames;
    private String username;
    private ByteBuffer msgLenBuffer = ByteBuffer.allocate(4);
    private ByteBuffer msgBuffer = ByteBuffer.allocate(baseBufferSize);
    private ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();


    public ChatClient(String name){
        this.username = name;
        initEnv();
    }
    public ChatClient(String serverIp, int serverPort, String name){
        this.host = serverIp;
        this.port = serverPort;
        this.username = name;
        initEnv();
    }

    public void initEnv(){
        try {
            // 初始化选择器
            this.selector = Selector.open();
            // 连接远程服务器
            this.socketChannel = SocketChannel.open(new InetSocketAddress(this.host, this.port));
            // 设置为非阻塞模式
            this.socketChannel.configureBlocking(false);
            // 注册选择器，并设置为read模式
            this.socketChannel.register(selector, SelectionKey.OP_READ);
            // 初始化用户名记录系统
            this.usernames = new HashMap<>();
            // 计算当前客户端的用户名
            usernames.put(host+":"+port, this.username);
            System.out.println(this.username + " login success!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 将信息发送出去，为了防止沾包和半包的问题，会首先发送长度信息，然后再发送具体信息
     * @param msgs
     * @return 返回信息状态，-1表示退出，0表示正常发送
     * @throws IOException
     */
    public int sendMsg(String msgs) throws IOException {
        if("exit".equals(msgs)){
            return -1;
        }else{
            String baseBlank = "aaaa";   // 先填充四个无意义字符，后续用于填充进去字符串长度
            // 用来存储数据的byte数组
            byte[] msgBytes;
            int flag;
            // 如果有多行数据则分开发送
            String[] splitedData = msgs.split("\n");
            for (String msg : splitedData) {
                msgBytes = (baseBlank + this.username + ":" + msg).getBytes();
                // 用以保证所有发送的信息长度都为4的倍数
                flag = msgBytes.length % 4;
                if(flag>0){
                    msgBytes = Arrays.copyOf(msgBytes, msgBytes.length + 4 - flag);
                }
                // 将消息长度信息写入消息体中
                System.arraycopy(int2Bytes(msgBytes.length-4), 0, msgBytes, 0, 4);
                // 发送
                this.socketChannel.write(ByteBuffer.wrap(msgBytes));
            }
            return 0;
        }
    }

    /**
     * 将int转为byte数组
     * @param num
     * @return
     */
    public static byte[] int2Bytes(int num) {
        byte[] bytes = new byte[4];
        //通过移位运算，截取低8位的方式，将int保存到byte数组
        bytes[0] = (byte)(num >>> 24);
        bytes[1] = (byte)(num >>> 16);
        bytes[2] = (byte)(num >>> 8);
        bytes[3] = (byte)num;
        return bytes;
    }

    /**
     * 执行读取信息操作
     * @param channel
     * @param msg
     * @throws IOException
     */
    public void readMsg(SocketChannel channel, ByteArrayOutputStream msg) throws IOException {
        System.out.println(msg.toString().trim());
        // 由于共用一个输出流，所以在使用完毕后要清空
        msg.reset();
    }

    /**
     * 执行监听服务端发来信息操作
     * @throws IOException
     */
    public void listen() throws IOException {
        while (!Thread.currentThread().isInterrupted()){
            int readyChannelNum = this.selector.select();
            if(readyChannelNum>0){
                Iterator<SelectionKey> iterator = this.selector.selectedKeys().iterator();
                while (iterator.hasNext()){
                    SelectionKey selectedKey = iterator.next();
                    if(selectedKey.isReadable()){
                        readData(selectedKey);
                    }
                    // 手动从迭代器中移除当前元素，防止重复处理
                    iterator.remove();
                }
            }
        }
    }

    /**
     * 读取数据。读取数据时需要处理沾包和半包的问题
     * @param selectedKey
     * @throws IOException
     */
    public void readData(SelectionKey selectedKey) throws IOException {
        SocketChannel channel = (SocketChannel)selectedKey.channel();
        ByteBuffer byteBuffer = this.msgBuffer;
        // 首先清空缓存
        byteBuffer.clear();

        // 首先从channel中读取一部分数据到buffer
        int count = channel.read(byteBuffer);
        // 将buffer从写状态转换为读状态
        byteBuffer.flip();
        // 首先获取信息的长度
        int msgLen = byteBuffer.getInt();
        int index = 0;
        int startOffset = 4;
        int getDataLen;
        // 只要还有数据就一直读取，只有在读取完一个阶段后才会执行其他任务
        while (count>0 || msgLen!=0){   // 只有读完所有完整的信息才算结束，出现半包则继续等待
            // 刚开始接收信息要首先获取int长度，共4个byte
            if(index==0) {
                index++;
                getDataLen = Math.min(count - startOffset, msgLen);
                byteArrayOutputStream.write(byteBuffer.array(), startOffset, getDataLen);
                // 判断当前是否读完了一条信息
                if(getDataLen==msgLen){   // 已经读完了一条信息
                    // 判断读完这条信息后是否把整个buffer读完了，防止出现沾包的情况
                    if(msgLen==(count - startOffset)){   // 读完这条信息后把整个buffer读完了，也就是没有出现沾包的情况
                        byteBuffer.clear();
                        if(byteArrayOutputStream.size()>0)
                            this.readMsg(channel, byteArrayOutputStream);
                        // 初始化信息，防止之后还有连续的信息需要读取，但环境不正确的问题
                        index = 0;
                        startOffset = 4;
                        msgLen -= getDataLen;
                        // 如果之后还有信息则继续读
                        byteBuffer.clear();
                        count = channel.read(byteBuffer);
                        // 将position置为开始
                        byteBuffer.rewind();
                        if(count>0){
                            msgLen = byteBuffer.getInt();
                        }
                    }else{   // 出现了沾包
                        // 首先将当前信息发送给所有客户端
                        if(byteArrayOutputStream.size()>0)
                            this.readMsg(channel, byteArrayOutputStream);
                        // 然后获取下一条信息的长度信息，并开始继续写入
                        // 首先获取新的一条信息的开始偏移位置
                        byteBuffer.position(startOffset+getDataLen);
                        // 计算数据真正的开始位置
                        startOffset = startOffset+getDataLen+4;
                        // 获取信息长度
                        msgLen = byteBuffer.getInt();
                        index = 0;
                        if(startOffset==count){
                            index++;
                            byteBuffer.clear();
                            count = channel.read(byteBuffer);
                            // 将position置为开始
                            byteBuffer.rewind();
                        }
                    }

                }else{   // 当前信息还没有读完，继续读
                    byteBuffer.clear();
                    msgLen -= getDataLen;
                    count = channel.read(byteBuffer);
                    // 将position置为开始
                    byteBuffer.rewind();
                }
            }else{
                getDataLen = Math.min(count, msgLen);
                byteArrayOutputStream.write(byteBuffer.array(), 0, getDataLen);
                // 判断当前是否读完了一条信息
                if(getDataLen==msgLen){   // 已经读完了一条信息
                    // 判断读完这条信息后是否把整个buffer读完了，防止出现沾包的情况
                    if(msgLen==count){   // 读完这条信息后把整个buffer读完了，也就是没有出现沾包的情况
                        if(byteArrayOutputStream.size()>0)
                            this.readMsg(channel, byteArrayOutputStream);
                        // 初始化信息，防止之后还有连续的信息需要读取，但环境不正确的问题
                        index = 0;
                        startOffset = 4;
                        msgLen -= getDataLen;
                        // 如果之后还有信息则继续读
                        byteBuffer.clear();
                        count = channel.read(byteBuffer);
                        // 将position置为开始
                        byteBuffer.rewind();
                        if(count>0){
                            msgLen = byteBuffer.getInt();
                        }
                    }else{   // 出现了沾包
                        // 首先将当前信息发送给所有客户端
                        if(byteArrayOutputStream.size()>0)
                            this.readMsg(channel, byteArrayOutputStream);
                        // 然后获取下一条信息的长度信息，并开始继续写入
                        // 首先获取新的一条信息的开始偏移位置
                        byteBuffer.position(msgLen);
                        // 计算数据真正的开始位置
                        startOffset = msgLen+4;
                        // 获取信息长度
                        msgLen = byteBuffer.getInt();
                        index = 0;
                        if(startOffset==count){
                            index++;
                            byteBuffer.clear();
                            count = channel.read(byteBuffer);
                            // 将position置为开始
                            byteBuffer.rewind();
                        }
                    }
                }else{   // 当前信息还没有读完，继续读
                    byteBuffer.clear();
                    msgLen -= getDataLen;
                    count = channel.read(byteBuffer);
                    // 将position置为开始
                    byteBuffer.rewind();
                }
            }
        }
    }

    // 退出程序
    public void logout() throws IOException {
        this.socketChannel.close();
        this.selector.close();
    }

    public static void main(String[] args) {
        ChatClient nowUser = new ChatClient("小蜉蝣");
        // 开启线程监听发来的请求
        Thread listenThread = new Thread(() -> {
            try {
                nowUser.listen();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        listenThread.start();

        // 模拟发送数据
//        new Thread(()->{
//            try {
//                while (true){
//                    mayfly1.sendMsg(mayfly1.username);
//                    Thread.sleep(100);
//                }
//            } catch (IOException | InterruptedException e) {
//                e.printStackTrace();
//            }
//        }).start();

        // 从控制台获取输入并发送
        Scanner scanner = new Scanner(System.in);
        while (true){
            String msg = scanner.nextLine();
            try {
                if(nowUser.sendMsg(msg)==-1){
                    // 中断监听线程
                    listenThread.interrupt();
                    // 循环等待监听线程结束
                    while (!listenThread.isInterrupted());
                    // 执行退出程序
                    nowUser.logout();
                    break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
