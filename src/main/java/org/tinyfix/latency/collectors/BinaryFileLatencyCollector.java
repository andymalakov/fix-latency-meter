package org.tinyfix.latency.collectors;

import org.tinyfix.latency.common.CaptureSettings;
import org.tinyfix.latency.util.TimeOfDayFormatter;

import java.io.*;
import java.util.Arrays;


/**
 * Records latency measurement in the following binary format format:
 * <pre>
 * OFFSET LENGTH DESCRIPTION
 * 00       1     Size of correlation ID (N)
 * 01       N     Correlation ID (ASCII text)
 * N+1      8     UTC timestamp of the moment we store this record (milliseconds count since 1/1/1970 0:00:00 UTC)
 * N+9      8     Latency (in microseconds)
 * </pre>
 * Main method formats results to the following CSV format:
 * <pre>time-of-day, correlation-id, latency (microseconds)</pre>
 */
public class BinaryFileLatencyCollector extends AbstractBinaryStreamLatencyRecorder {

    public BinaryFileLatencyCollector(String filename) throws IOException {
        this(new BufferedOutputStream(new FileOutputStream(filename), 8192));
    }

    public BinaryFileLatencyCollector(OutputStream os) throws IOException {
        super(os);
        assert CaptureSettings.MAX_CORRELATION_ID_LENGTH <= 256; // we fit correlation ID length into single byte
    }

    @Override
    public synchronized void recordLatency(byte[] buffer, int offset, int length, long inboundTimestamp, long outboundTimestamp) {
        assert length < 256; // must fit into byte
        try {
            os.write(length);
            os.write(buffer, offset, length);
            writeLong (System.currentTimeMillis());
            writeLong (outboundTimestamp - inboundTimestamp);
        } catch (IOException e) {
            throw new RuntimeException("Error writing latency stats", e);
        }
    }

    public static void main (String ...args) throws Exception {
        final String inputFile = args[0];
        final String outputFile = args[1];

        final int numberOfPoints = (args.length > 2) ? Integer.parseInt(args[2]) : 0;
        final int [] sortedLatencies = (numberOfPoints > 0) ? new int [numberOfPoints] : null;

        final char [] timestampBuffer = new char [TimeOfDayFormatter.FORMAT_LENGTH];
        final byte [] correlationIdBuffer = new byte [256 + 2*SIZE_OF_INT];
        final InputStream is = new FileInputStream(inputFile);
        final PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(outputFile), 8192));

        int signalCount = 0;
        try {
            while (true) {
                int signalLength = is.read();
                if (signalLength == -1)
                    break; // EOF
                int totalRowSize = signalLength + 2*SIZE_OF_INT;
                int bytesRead = is.read(correlationIdBuffer, 0 , totalRowSize);
                if (bytesRead != totalRowSize) {
                    System.out.println("Unexpected EOF while reading signal #" + (signalCount+1));
                }

                long timestamp = readLong(correlationIdBuffer, signalLength);
                long latency = readLong(correlationIdBuffer, signalLength + SIZE_OF_INT);

                TimeOfDayFormatter.formatTimeOfDay(timestamp, timestampBuffer);
                writer.print(timestampBuffer);

                writer.print(',');
                writer.print(new String(correlationIdBuffer, 0, signalLength));
                writer.print(',');
                writer.print(latency);
                writer.print('\n');


                if (sortedLatencies != null) {
                    if (latency > Integer.MAX_VALUE)
                        throw new Exception("Latency value exceeds INT32: " + latency);
                    sortedLatencies[signalCount] = (int)latency;
                }

                signalCount++;
            }
        } finally {
            is.close();
            writer.close();
        }

        if (sortedLatencies != null && signalCount > 0) {

            System.out.println("Sorting " + signalCount + " results");
            Arrays.sort(sortedLatencies, 0, signalCount);
            System.out.println("MIN: " + sortedLatencies[0]);
            System.out.println("MAX: " + sortedLatencies[signalCount-1]);
            System.out.println("MEDIAN: " + sortedLatencies[signalCount/2]);

            System.out.println("99.000%: " + sortedLatencies[ (int)   (99L*signalCount/100)]);
            System.out.println("99.900%: " + sortedLatencies[ (int)  (999L*signalCount/1000)]);
            System.out.println("99.990%: " + sortedLatencies[ (int) (9999L*signalCount/10000)]);
            System.out.println("99.999%: " + sortedLatencies[ (int)(99999L*signalCount/100000)]);
        }


    }
}
