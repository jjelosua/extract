package org.icij.extract.tasks;

import org.icij.concurrent.BooleanSealableLatch;
import org.icij.extract.core.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;

import java.lang.management.ManagementFactory;

import com.sun.management.OperatingSystemMXBean;
import org.icij.extract.tasks.factories.*;
import org.icij.task.DefaultTask;
import org.icij.task.annotation.Option;
import org.icij.task.annotation.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spew extracted text from files to an output.
 *
 * @author Matthew Caruana Galizia <mcaruana@icij.org>
 * @since 1.0.0-beta
 */
@Task("Extract from files.")
@Option(name = "queue-type", description = "Set the queue backend type. For now, the only valid value is " +
		"\"redis\".", parameter = "type", code = "q")
@Option(name = "queue-name", description = "The name of the queue, the default of which is type-dependent" +
		".", parameter = "name")
@Option(name = "queue-buffer", description = "The size of the internal file path buffer used by the queue" +
		".", parameter = "size")
@Option(name = "queue-poll", description = "Time to wait when polling the queue e.g. \"5s\" or \"1m\". " +
		"Defaults to 0.", parameter = "duration")
@Option(name = "report-type", description = "Set the report backend type. For now, the only valid value is" +
		" \"redis\".", parameter = "type", code = "r")
@Option(name = "report-name", description = "The name of the report, the default of which is " +
		"type-dependent.", parameter = "name")
@Option(name = "redis-address", description = "Set the Redis backend address. Defaults to " +
		"127.0.0.1:6379.", parameter = "address")
@Option(name = "include-pattern", description = "Glob pattern for matching files e.g. \"**/*.{tif,pdf}\". " +
		"Files not matching the pattern will be ignored.", parameter = "pattern")
@Option(name = "exclude-pattern", description = "Glob pattern for excluding files and directories. Files " +
		"and directories matching the pattern will be ignored.", parameter = "pattern")
@Option(name = "follow-symlinks", description = "Follow symbolic links, which are not followed by default" +
		".")
@Option(name = "include-hidden-files", description = "Don't ignore hidden files. On DOS file systems, this" +
		" means all files or directories with the \"hidden\" file attribute. On all other file systems, this means " +
		"all file or directories starting with a dot. Hidden files are ignored by default.")
@Option(name = "include-os-files", description = "Include files and directories generated by common " +
		"operating systems. This includes \"Thumbs.db\" and \".DS_Store\". The list is not determined by the current " +
		"operating system. OS-generated files are ignored by default.")
@Option(name = "path-base", description = "This is useful if your mount path for files varies from system " +
		"to another, or if you simply want to hide the base of a path. For example, if you're working with a path " +
		"that looks like \"/home/user/data\", specify \"/home/user/\" as the value for this option so that all queued" +
		" paths start with \"data/\".")
@Option(name = "index-type", description = "Specify the index type. For now, the only valid value is " +
		"\"solr\" (the default).", parameter = "type")
@Option(name = "soft-commit", description = "Performs a soft commit. Makes index changes visible while " +
		"neither fsync-ing index files nor writing a new index descriptor. This could lead to data loss if Solr is " +
		"terminated unexpectedly.")
@Option(name = "index-address", description = "Index core API endpoint address.", code = "s", parameter =
		"url")
@Option(name = "index-server-certificate", description = "The index server's public certificate, used for" +
		" certificate pinning. Supported formats are PEM, DER, PKCS #12 and JKS.", parameter = "path")
@Option(name = "index-verify-host", description = "Verify the index server's public certificate against " +
		"the specified host. Use the wildcard \"*\" to disable verification.", parameter = "hostname")
@Option(name = "output-format", description = "Set the output format. Either \"text\" or \"HTML\". " +
		"Defaults to text output.", parameter = "type")
@Option(name = "embed-handling", description = "Set the embed handling mode. Either \"ignore\", " +
		"\"extract\" or \"embed\". When set to extract, embeds are parsed and the output is in-lined into the main " +
		"output. In embed mode, embeds are not parsed but are in-lined as a data URI representation of the raw embed " +
		"data. The latter mode only applies when the output format is set to HTML. Defaults to extracting.",
		parameter = "type")
@Option(name = "ocr-language", description = "Set the language used by Tesseract. If none is specified, " +
		"English is assumed. Multiple  languages may be specified, separated by plus characters. Tesseract uses " +
		"3-character ISO 639-2 language codes.", parameter = "language")
@Option(name = "ocr-timeout", description = "Set the timeout for the Tesseract process to finish e.g. \"5s\" or \"1m\". " +
		"Defaults to 12 hours.", parameter = "duration")
@Option(name = "ocr", description = "Enable or disable automatic OCR. On by default.")
@Option(name = "working-directory", description = "Set the working directory from which to resolve paths " +
		"for files passed to the extractor.", parameter = "path")
@Option(name = "output-metadata", description = "Output metadata along with extracted text. For the " +
		"\"file\" output type, a corresponding JSON file is created for every input file. With indexes, metadata " +
		"fields are set using an optional prefix. On by default.")
@Option(name = "tag", description = "Set the given field to a corresponding value on each document output" +
		".", parameter = "name-value-pair")
@Option(name = "output-encoding", description = "Set the text output encoding. Defaults to UTF-8.",
		parameter = "character-set")
@Option(name = "id-field", description = "Index field for an automatically generated identifier. The ID " +
		"for the same file is guaranteed not to change if the path doesn't change. Defaults to \"id\".", code = "i",
		parameter = "name")
@Option(name = "text-field", description = "Index field for extracted text. Defaults to \"text\".", code =
		"t", parameter = "name")
@Option(name = "path-field", description = "Index field for the file path. Defaults to \"path\".", parameter
		= "name", code = "p")
@Option(name = "id-algorithm", description = "The hashing algorithm used for generating index document " +
		"identifiers from paths e.g. \"SHA-224\". Defaults to \"SHA-256\".", parameter = "algorithm")
@Option(name = "metadata-prefix", description = "Prefix for metadata fields added to the index. " +
		"Defaults to \"metadata_\".", parameter = "name")
@Option(name = "jobs", description = "The number of documents to process at a time. Defaults to the number" +
		" of available processors.", parameter = "number")
@Option(name = "commit-interval", description = "Commit to the index every time the specified number of " +
		"documents is added. Disabled by default. Consider using the \"autoCommit\" \"maxDocs\" directive in your " +
		"Solr update handler configuration instead.", parameter = "number")
@Option(name = "commit-within", description = "Instruct Solr to automatically commit a document after the " +
		"specified amount of time has elapsed since it was added. Disabled by default. Consider using the " +
		"\"autoCommit\" \"maxTime\" directive in your Solr update handler configuration instead.", parameter =
		"duration")
@Option(name = "atomic-writes", description = "Make atomic updates to the index. If your schema contains " +
		"fields that are not included in the payload, this prevents their values, if any, from being erased.")
@Option(name = "fix-dates", description = "Fix invalid dates. Tika's image metadata extractor will " +
		"generate non-ISO compliant dates if the the timezone is not available in the source metadata. When this " +
		"option is on \"Z\" is appended to non-compliant dates, making them compatible with the Solr date field type." +
		" On by default.")
@Option(name = "output-directory", description = "Directory to output extracted text. Defaults to the " +
		"current directory.", parameter = "path")
@Option(name = "output-type", description = "Set the output type. Either \"file\", \"stdout\" or \"solr\"" +
		".", parameter = "type", code = "o")
public class SpewTask extends DefaultTask<Long> {

	private static final Logger logger = LoggerFactory.getLogger(SpewTask.class);

	private void checkMemory() {
		final OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)
				ManagementFactory.getOperatingSystemMXBean();
		final long maxMemory = Runtime.getRuntime().maxMemory();

		if (maxMemory < (os.getTotalPhysicalMemorySize() / 4)) {
			logger.warn(String.format("Memory available to JVM (%s) is less than 25%% of available system memory. " +
					"You should probably increase it.", FileUtils.byteCountToDisplaySize(maxMemory)));
		}
	}

	@Override
	public Long run(final String[] paths) throws Exception {
		checkMemory();

		try (final Report report = ReportFactory.createReport(options);
		     final Spewer spewer = SpewerFactory.createSpewer(options);
		     final PathQueue queue = PathQueueFactory.createQueue(options)) {

			return spew(report, spewer, queue, paths);
		}
	}

	@Override
	public Long run() throws Exception {
		return run(null);
	}

	private Long spew(final Report report, final Spewer spewer, final PathQueue queue, final String[] paths) throws
			Exception {
		final int parallelism = options.get("jobs").asInteger().orElse(ExtractingConsumer.defaultPoolSize());
		logger.info(String.format("Processing up to %d file(s) in parallel.", parallelism));

		final Extractor extractor = ExtractorFactory.createExtractor(options);
		final ExtractingConsumer consumer = new ExtractingConsumer(spewer, extractor, parallelism);
		final PathQueueDrainer drainer = new PathQueueDrainer(queue, consumer);

		final Optional<Duration> queuePoll = options.get("queue-poll").asDuration();

		if (queuePoll.isPresent()) {
			drainer.setPollTimeout(queuePoll.get());
		}

		if (null != report) {
			consumer.setReporter(new Reporter(report));
		}

		final Future<Long> draining;
		final Long drained;

		if (null != paths && paths.length > 0) {
			final Scanner scanner = new ScannerFactory()
					.withQueue(queue)
					.withOptions(options)
					.withLatch(new BooleanSealableLatch())
					.create();
			final List<Future<Path>> scanning = scanner.scan(options.get("path-base").value().orElse(null), paths);

			// Set the latch that will be waited on for polling, then start draining in the background.
			drainer.setLatch(scanner.getLatch());
			draining = drainer.drain();

			// Start scanning in a background thread but block until every path has been scanned and queued.
			for (Future<Path> scan : scanning) scan.get();

			// Only a short timeout is needed when awaiting termination, because the call to get the result of each
			// job is blocking and by the time `awaitTermination` is reached the jobs would have finished.
			scanner.shutdown();
			scanner.awaitTermination(5, TimeUnit.SECONDS);
		} else {

			// Start draining in a background thread.
			draining = drainer.drain();
		}

		// Block until every path in the queue has been consumed.
		drained = draining.get();

		logger.info(String.format("Drained %d files.", drained));

		// Shut down the drainer. Use a short timeout because all jobs should have finished.
		drainer.shutdown();
		drainer.awaitTermination(5, TimeUnit.SECONDS);

		// Use a long timeout because some files might still be processing.
		consumer.shutdown();
		consumer.awaitTermination(1, TimeUnit.DAYS);

		return drained;
	}
}
