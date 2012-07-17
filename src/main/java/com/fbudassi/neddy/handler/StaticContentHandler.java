package com.fbudassi.neddy.handler;

import com.fbudassi.neddy.config.Config;
import com.fbudassi.neddy.config.DirectoryIndex;
import com.fbudassi.neddy.util.DateUtil;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;
import javax.activation.FileTypeMap;
import javax.activation.MimetypesFileTypeMap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.jboss.netty.handler.codec.http.HttpHeaders.*;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import org.jboss.netty.handler.codec.http.*;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.*;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static Content Handler.
 *
 * @author federico
 */
public class StaticContentHandler extends SimpleChannelUpstreamHandler {

    private static final Logger logger = LoggerFactory.getLogger(StaticContentHandler.class);
    // Some configuration variables.
    private static final String WWWROOT = Config.getValue(Config.KEY_WWWROOT);
    private static final String SERVERNAME = Config.getValue(Config.KEY_SERVERNAME);
    // Collection with mime types.
    private static final FileTypeMap MIME_TYPES = MimetypesFileTypeMap.getDefaultFileTypeMap();
    // List of default names of index files.
    private static final List<String> DEFAULT_NAMES = DirectoryIndex.getFileNames();

    /**
     * Executed when an HTTP request is received.
     *
     * @param ctx
     * @param e
     * @throws Exception
     */
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        HttpRequest request = (HttpRequest) e.getMessage();

        // Neddy supports GET as the only allowed HTTP method.
        if (request.getMethod() != GET) {
            sendError(ctx, METHOD_NOT_ALLOWED);
            return;
        }

        // Log client information and URI requested.
        String remoteAddress = ctx.getChannel().getRemoteAddress().toString();
        String userAgent = request.getHeader(USER_AGENT);
        logger.info("{} - {} - requested: {}",
                new Object[]{remoteAddress, userAgent, request.getUri()});

        // Check for file validity.
        final String path = sanitizeUri(request.getUri());
        if (path == null) {
            sendError(ctx, FORBIDDEN);
            return;
        }

        File file = new File(path);
        if (file.isHidden() || !file.exists()) {
            sendError(ctx, NOT_FOUND);
            return;
        }

        if (file.isDirectory()) {
            // Look for a valid index file in the directory (e.g. index.html).
            for (String fileName : DEFAULT_NAMES) {
                File indexFile = new File(path + fileName);
                if (indexFile.exists() && indexFile.isFile() && !indexFile.isHidden()) {
                    file = indexFile;
                    break;
                }
            }
        }

        if (!file.isFile()) {
            sendError(ctx, FORBIDDEN);
            return;
        }

        // Open file.
        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException fnfe) {
            sendError(ctx, NOT_FOUND);
            return;
        }
        long fileLength = raf.length();

        // Set some HTTP Headers.
        HttpResponse response = buildResponseHeaders(request, file, fileLength);

        // Get client channel to write the response.
        Channel ch = e.getChannel();

        // Write the initial line and the header.
        ch.write(response);

        // Use zero-copy (no need to spend time copying buffers) through java.nio filechannels.
        // The associated file is closed after transfer is complete.
        // It may use DMA to do the transfer or take advantage of another SO capability.
        // See transferTo in: http://download.oracle.com/javase/6/docs/api/java/nio/channels/FileChannel.html
        final FileRegion region = new DefaultFileRegion(raf.getChannel(), 0, fileLength, true);
        ChannelFuture writeFuture = ch.write(region);

        // Decide whether to close the connection or not.
        if (!isKeepAlive(request)) {
            // Close the connection when the whole content is written out.
            writeFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Executed when an exception is caught.
     *
     * @param ctx
     * @param e
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        Channel ch = e.getChannel();
        Throwable cause = e.getCause();
        if (cause instanceof TooLongFrameException) {
            sendError(ctx, BAD_REQUEST);
            return;
        }

        if (ch.isConnected()) {
            sendError(ctx, INTERNAL_SERVER_ERROR);
        }
        logger.error("Error in StaticContentHandler", cause);
    }

    /**
     * Cleans the request Uri.
     *
     * @param uri
     * @return
     */
    private String sanitizeUri(String uri) {
        // Decode the path.
        try {
            uri = URLDecoder.decode(uri, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            try {
                uri = URLDecoder.decode(uri, "ISO-8859-1");
            } catch (UnsupportedEncodingException e1) {
                throw new Error();
            }
        }

        // Convert file separators.
        uri = uri.replace('/', File.separatorChar);

        // Security checks.
        if (uri.contains(File.separator + ".")
                || uri.contains("." + File.separator)
                || uri.startsWith(".") || uri.endsWith(".")) {
            return null;
        }

        // Convert to absolute path.
        return WWWROOT + uri;
    }

    /**
     * Builds the basic HTTP headers for the static content handler.
     *
     * @param request
     * @param file
     * @param fileLength
     * @return
     */
    private HttpResponse buildResponseHeaders(HttpRequest request, File file, long fileLength) {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);

        // Get mime type.
        String mimeType = MIME_TYPES.getContentType(file);
        if ("text/plain".equals(mimeType)) {
            mimeType += "; charset=utf-8";
        }

        // Set some HTTP Headers.
        setHeader(response, DATE, DateUtil.getCurrent());
        setHeader(response, SERVER, SERVERNAME);
        setHeader(response, LAST_MODIFIED, DateUtil.formatDate(file.lastModified()));
        setHeader(response, CONTENT_TYPE, mimeType);
        setContentLength(response, fileLength);

        // Workaround for Apache Benchmark bug.
        // See http://blog.lolyco.com/sean/2009/11/25/ab-apache-bench-hanging-with-k-keep-alive-switch/
        if (isKeepAlive(request)) {
            setHeader(response, CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        } else {
            setHeader(response, CONNECTION, HttpHeaders.Values.CLOSE);
        }

        return response;
    }

    /**
     * Handy method to inform of an error in the request and close the
     * connection.
     *
     * @param ctx
     * @param status
     */
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        // Log some information about the reason.
        logger.info("Error in request: {} - {}", status.getCode(), status.getReasonPhrase());

        // Send error response.
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, status);
        response.setHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.setContent(ChannelBuffers.copiedBuffer(
                "Failure: " + status.toString() + "\r\n",
                CharsetUtil.UTF_8));

        // Close the connection as soon as the error message is sent.
        ctx.getChannel().write(response).addListener(ChannelFutureListener.CLOSE);
    }
}
