package sim;

import java.io.FileNotFoundException;
import org.apache.log4j.*;

import sim.constraints.HistoryReconstructor;
public class ConstraintsMain {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		LogManager.shutdown();
		try {
			HistoryReconstructor reconstructor = new HistoryReconstructor("sonoma4a.xml", "bstrace1.txt");
			reconstructor.reconstruct();
			reconstructor.generateConstraints();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
}
