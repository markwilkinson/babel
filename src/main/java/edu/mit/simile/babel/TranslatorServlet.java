package edu.mit.simile.babel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.Encoder;
import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.openrdf.sail.Sail;
import org.openrdf.sail.SailInitializationException;
import org.openrdf.sail.memory.MemoryStore;

import com.oreilly.servlet.multipart.FilePart;
import com.oreilly.servlet.multipart.MultipartParser;
import com.oreilly.servlet.multipart.ParamPart;
import com.oreilly.servlet.multipart.Part;

import edu.mit.simile.babel.SemanticType;

public class TranslatorServlet extends HttpServlet {
	final static private long serialVersionUID = 2083937775584527297L;
	final static private Logger s_logger = Logger.getLogger(TranslatorServlet.class);
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		
        String[] params = StringUtils.splitPreserveAllTokens(request.getQueryString(), '&');
		Writer writer = new BufferedWriter(
			new OutputStreamWriter(response.getOutputStream(), "UTF-8"));
		try {
			internalDoPost(request, response, params, writer);
		} finally {
			writer.close();
		}
	}
	
	@SuppressWarnings("unchecked")
	protected Enumeration<String> getParameterNames(HttpServletRequest request) {
		return (Enumeration<String>) request.getParameterNames();
	}
	
	protected boolean internalDoPost(
		HttpServletRequest 	request, 
		HttpServletResponse response,
		String[]			params,
		Writer				writer
	) throws ServletException, IOException {
		Properties 		readerProperties = new Properties();
		Properties 		writerProperties = new Properties();
		String			readerName = null;
		String			writerName = null;
		String			mimetype = null;
		
		/*
		 * Parse parameters
		 */
        for (int i = 0; i < params.length; i++) {
            String param = params[i];
            int equalIndex = param.indexOf('=');

            if (equalIndex >= 0) {
                String rawName = param.substring(0, equalIndex);
                String rawValue = param.substring(equalIndex + 1);

                String name = decode(rawName);
                String value = decode(rawValue);

				if (name.startsWith("in-")) {
					readerProperties.setProperty(name.substring(3), value);
				} else if (name.startsWith("out-")) {
					writerProperties.setProperty(name.substring(4), value);
				} else if (name.equals("reader")) {
					readerName = value;
				} else if (name.equals("writer")) {
					writerName = value;
				} else if (name.equals("mimetype")) {
					mimetype = value;
				}
            }
		}
		
		/*
		 * Instantiate converters
		 */
		if (readerName == null) {
			s_logger.warn("No reader name in request");
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return false;
		} else if (writerName == null) {
			s_logger.warn("No writer name in request");
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return false;
		}
		
		BabelReader babelReader = Babel.s_readers.get(readerName); 
		BabelWriter babelWriter = Babel.s_writers.get(writerName); 
		if (babelReader == null) {
			s_logger.warn("No reader of name " + readerName);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return false;
		} else if (babelWriter == null) {
			s_logger.warn("No writer of name " + writerName);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return false;
		}
		
		/*
		 * Check compatibility
		 */
		SemanticType readerType = babelReader.getSemanticType();
		SemanticType writerType = babelWriter.getSemanticType();
		if (!writerType.getClass().isInstance(readerType)) {
			s_logger.warn(
				"Writer " + writerType.getClass().getName() + 
				" cannot take input from reader " + readerType.getClass().getName()
			);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return false;
		}
		
		/*
		 * Read in data, convert, and write result out
		 */
		MemoryStore store = new MemoryStore();
		try {
			store.initialize();
			try {
				readAndConvert(babelReader, store, readerProperties, request);
				
				setContentEncodingAndMimetype(babelWriter, response, mimetype);
				
				writeResult(babelWriter, store, writerProperties, writer);
				
				return true;
			} catch (BabelException e) {
				e.printStackTrace();
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			} finally {
				store.shutDown();
			}
		} catch (SailInitializationException e) {
			s_logger.error(e);
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		return false;
	}
	
	protected void readAndConvert(
		BabelReader 		converter,
		Sail				sail,
		Properties			readerProperties,
		HttpServletRequest	request
	) throws BabelException {
		try {
			MultipartParser parser = new MultipartParser(request, 5 * 1024 * 1024);
			
			Part part = null;
			while ((part = parser.readNextPart()) != null) {
				if (part.isFile()) {
					FilePart filePart = (FilePart) part;
					Reader reader = new InputStreamReader(filePart.getInputStream());
					try {
						converter.read(reader, sail, readerProperties);
					} finally {
						reader.close();
					}
				} else if (part.isParam()) {
					ParamPart paramPart = (ParamPart) part;
					String paramName = paramPart.getName();
					if (paramName.equals("raw-text")) {
						StringReader reader = new StringReader(paramPart.getStringValue());
						try {
							converter.read(reader, sail, readerProperties);
						} finally {
							reader.close();
						}
					} else if (paramName.equals("url")) {
						URLConnection connection = new URL(paramPart.getStringValue()).openConnection();
						connection.setConnectTimeout(5000);
						connection.connect();
						
						InputStream inputStream = connection.getInputStream();
						String encoding = connection.getContentEncoding();
						
						Reader reader = new InputStreamReader(
							inputStream, (encoding == null) ? "ISO-8859-1" : encoding);
									
						try {
							converter.read(reader, sail, readerProperties);
						} finally {
							reader.close();
						}
					}
				}
			}
		} catch (Exception e) {
			s_logger.error(e);
			throw new BabelException(e);
		}
	}
	
	protected void writeResult(
		BabelWriter 		babelWriter, 
		Sail 				sail, 
		Properties 			writerProperties,
		Writer				writer
	) throws BabelException {
		try {
			babelWriter.write(writer, sail, writerProperties);
		} catch (Exception e) {
			s_logger.error(e);
			throw new BabelException(e);
		}
	}
	
	protected void setContentEncodingAndMimetype(
			BabelWriter writer, HttpServletResponse response, String mimetype) {
		response.setCharacterEncoding("UTF-8");
		response.setContentType(
			(mimetype == null || mimetype.equals("default")) ? 
					writer.getSerializationFormat().getMimetype() :
					mimetype
		);
	}
	
    private static final String s_urlEncoding = "UTF-8";
    private static final URLCodec s_codec = new URLCodec();
    
    static public String decode(String s) {
        try {
            return s_codec.decode(s, s_urlEncoding);
        } catch (Exception e) {
            throw new RuntimeException("Exception decoding " + s + " with " + s_urlEncoding + " encoding.");
        }
    }
}