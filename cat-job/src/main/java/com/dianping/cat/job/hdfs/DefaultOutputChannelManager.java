package com.dianping.cat.job.hdfs;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;

import com.dianping.cat.job.configuration.HdfsConfig;
import com.dianping.cat.message.spi.MessagePathBuilder;
import com.dianping.cat.message.spi.MessageTree;
import com.site.lookup.ContainerHolder;
import com.site.lookup.annotation.Inject;

public class DefaultOutputChannelManager extends ContainerHolder implements OutputChannelManager, Initializable, LogEnabled {
	@Inject
	private MessagePathBuilder m_builder;

	@Inject
	private String m_baseDir = "target/hdfs";

	private URI m_serverUri;

	@Inject
	private HdfsConfig m_hdfsConfig;

	@Inject
	private String m_type = "data";

	private FileSystem m_fs;

	private Path m_basePath;

	private Map<String, OutputChannel> m_channels = new HashMap<String, OutputChannel>();

	private Map<String, Integer> m_indexes = new HashMap<String, Integer>();

	private Logger m_logger;

	@Override
	public void cleanupChannels() {
		try {
			List<String> expired = new ArrayList<String>();

			for (Map.Entry<String, OutputChannel> e : m_channels.entrySet()) {
				if (e.getValue().isExpired()) {
					expired.add(e.getKey());
				}
			}

			for (String path : expired) {
				OutputChannel channel = m_channels.remove(path);

				closeChannel(channel);
			}
		} catch (Exception e) {
			m_logger.warn("Error when doing cleanup!", e);
		}
	}

	@Override
	public void closeAllChannels() {
		for (OutputChannel channel : m_channels.values()) {
			closeChannel(channel);
		}
	}

	@Override
	public void closeChannel(OutputChannel channel) {
		channel.close();
		super.release(channel);
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public void initialize() throws InitializationException {
		try {
			Configuration config = new Configuration();
			FileSystem fs;
			String dataPath = null;
			if ("data".equals(this.m_type)) {
				dataPath = this.m_hdfsConfig.getDataPath();
			} else if ("dump".equals(this.m_type)) {
				dataPath = this.m_hdfsConfig.getDumpPath();
			}
			if (dataPath.startsWith("hdfs://")) {
				this.setServerUri(dataPath);
			} else {
				this.m_baseDir = dataPath;
			}
			config.setInt("io.file.buffer.size", 8192);
			if (m_serverUri == null) {
				fs = FileSystem.getLocal(config);
				m_basePath = new Path(fs.getWorkingDirectory(), m_baseDir);
			} else {
				fs = FileSystem.get(m_serverUri, config);
				m_basePath = new Path(new Path(m_serverUri), m_baseDir);
			}

			m_fs = fs;
		} catch (Exception e) {
			throw new InitializationException("Error when getting HDFS file system.", e);
		}
	}

	@Override
	public OutputChannel openChannel(MessageTree tree, boolean forceNew) throws IOException {
		String path = m_builder.getMessagePath(tree.getDomain(), new Date(tree.getMessage().getTimestamp()));

		return openChannel(path, forceNew);
	}

	public OutputChannel openChannel(String path, boolean forceNew) throws IOException {
		OutputChannel channel = m_channels.get(path);

		if (channel == null) {
			Path file = new Path(m_basePath, path);
			FSDataOutputStream out = m_fs.create(file);

			channel = lookup(OutputChannel.class);
			channel.initialize(out);

			m_indexes.put(path, 0);
			m_channels.put(path, channel);
		} else if (forceNew) {
			int index = m_indexes.get(path);

			closeChannel(channel);
			m_indexes.put(path, ++index);

			Path file = new Path(m_basePath, path + "-" + index);
			FSDataOutputStream out = m_fs.create(file);

			channel = lookup(OutputChannel.class);
			channel.initialize(out);
			m_channels.put(path, channel);
		}

		return channel;
	}

	public void setBaseDir(String baseDir) {
		m_baseDir = baseDir;
	}

	public void setServerUri(String serverUri) {
		if (serverUri != null && serverUri.length() > 0) {
			m_serverUri = URI.create(serverUri);
		}
	}

	public void setType(String type) {
		this.m_type = type;
	}
}
