package uiautomator.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

import uiautomator.ExportHandle;

public class WebPost {
    private  BufferedReader reader;
    private  StringBuffer response;
    public WebPost(String httpMethodStr, String parameterData, String token) throws Exception {
        try {
            URL url = new URL(httpMethodStr);//创建连接
            URLConnection urlConnection = url.openConnection();//声明一个抽象类URLConnection的引用urlConnection
            // 此处的urlConnection对象实际上是根据URL的请求协议(此处是http)生成的URLConnection类的子类HttpURLConnection,故此处最好将其转化为
            // HttpURLConnection类型的对象,以便用到HttpURLConnection更多的API.如下:
            HttpURLConnection connection = (HttpURLConnection) urlConnection; //声明一个抽象类HttpURLConnection的引用connection
            //URLConnection和HttpURLConnection使用的都是java.net中的类，属于标准的java接口。
            //HttpURLConnection继承自URLConnection,差别在与HttpURLConnection仅仅针对Http连接。
            connection.setRequestMethod("POST");//设定请求方式为"POST"，默认为"GET"
            connection.setDoOutput(true);//设置是否向HttpUrlConnction输出，因为这个是POST请求，参数要放在http正文内，因此需要设为true，默认情况下是false
            connection.setDoInput(true);//设置是否向HttpUrlConnection读入，默认情况下是true
            connection.setUseCaches(false);//POST请求不能使用缓存（POST不能被缓存）
            connection.setInstanceFollowRedirects(true);//设置只作用于当前的实例
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Cookie", "token=" + token);
            connection.connect();//connect()函数会根据HttpURLConnection对象的配置值 生成http头部信息，因此在调用connect函数之前，就必须把所有的配置准备好
            //HttpURLConnection是基于HTTP协议的，其底层通过socket通信实现。如果不设置超时（timeout），在网络异常的情况下，可能会导致程序僵死而不继续往下执行。
            connection.setConnectTimeout(20 * 1000);//设置连接主机超时（单位：毫秒）
            connection.setReadTimeout(20 * 1000);//设置从主机读取数据超时（单位：毫秒）

            //正文的内容是通过outputStream流写入的，实际上outputStream不是一个网络流，充其量是个字符串流，往里面写入的东西不会立即发送到网络，
            //而是存在于内存缓冲区中，待outputStream流关闭时，根据输入的内容生成http正文。至此，http请求的东西已经全部准备就绪
            DataOutputStream dataOutputStream = new DataOutputStream(connection.getOutputStream());
            byte[] t = parameterData.getBytes(StandardCharsets.UTF_8);
            dataOutputStream.write(t);
            dataOutputStream.flush();
            dataOutputStream.close();

            //对outputStream的写操作，又必须要在inputStream的读操作之前
            InputStream inputStream;
//            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)  {
//                inputStream = connection.getErrorStream();
//            }
//            else  {
                inputStream = connection.getInputStream();
//            }
            //读取响应
            reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            String lines;
            response = new StringBuffer("");
            while ((lines = reader.readLine()) != null) {
                response.append(lines);
                response.append("\n");
            }
        } catch (Throwable e) {
            String errorMsg = ExportHandle.FLAG_HTTP_ERROR + "WebPost->"  + httpMethodStr + "\n" + Log.getStackTraceString(e);
            Log.e("WebPost", errorMsg);
            response = new StringBuffer(errorMsg);
        }
    }

    public String returnResult() {
        if (response == null || response.toString().contains(ExportHandle.FLAG_HTTP_ERROR)) {
            throw new IllegalStateException("" + response);
        }
        return response.toString();
    }
}