package sim.constraints;

import java.io.FileNotFoundException;
import org.apache.log4j.*;
public class ConstraintsMain {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		String configFile, traceFile;
		if (args.length != 2) {
			System.err.println("Missing arguments: config file, trace file");
			return;
		}
		else {
			configFile = args[0];
			traceFile = args[1];
		}
		try {
			HistoryReconstructor reconstructor = new HistoryReconstructor(configFile, traceFile);
			reconstructor.reconstruct();
			reconstructor.generateConstraints();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
}
