/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package sim.nodes;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;

import Jama.*;

/**
 * 
 * @author Yi Zhang
 */
public class NetworkConfiguration {
	
	private static DataProvider globalDataProvider = null;
	private static Network globalNetwork = null;
	
	public int timeSteps;
	public int clusterCount;
	public int nodeCount;
	public double epsilon1;
	public double epsilon2;

	//public BaseStation baseStation;
	//public Cluster[] clusters;
	//public int[] inverseClusterTable;
	
	public static Network createNetwork(String filename) {
		return createNetwork(filename, true);
	}
	
	public static Network createNetwork(String filename, boolean allocate) {
		
		//Network net = new Network();
		
		Network net = new Network();
		globalNetwork = net;
		HierarchicalConfiguration config = null;
		try {
			config = new XMLConfiguration(filename);
		} catch (ConfigurationException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}

		String dataFile = config.getString("dataFile");
		net.timeSteps = config.getInt("timeSteps", 0);
		net.nodeCount = config.getInt("nodeCount", 0);
		net.epsilon1 = config.getDouble("epsilon1", .5);
		net.epsilon2 = config.getDouble("epsilon2", .5);
		net.failureRate1 = config.getDouble("failureRate1");
		net.failureRate2 = config.getDouble("failureRate2");
		net.nodeRedundancy = config.getInt("nodeRedundancy");
		net.nodeRedundancyFromHeadToBase = config.getInt("nodeRedundancyFromHeadToBase");
		net.headRedundancy = config.getInt("headRedundancy");
		//net.headHistorySize = config.getInt("headHistorySize",4);
		
		// read clusters
		List clusters = config.configurationsAt("clusters.cluster");
		net.clusterCount = clusters.size();

		BaseStation bs = net.createBaseStation();

		// read each cluster
		int i = 0;
		
		for (Object obj : clusters) {
			//Cluster cluster = net.clusters[i] = new Cluster();
			HierarchicalConfiguration sub = (HierarchicalConfiguration) obj;
			List nodeList = sub.getList("nodes");
			Cluster cluster = bs.createCluster(i, nodeList.size());			

			//cluster.setNodeCount(nodeList.size());
			
			// let cluster remember its members' global ids
			// for mapping of data
			cluster.nodeGlobalIDs = new int[cluster.getNodeCount()];
			for (int j = 0; j < cluster.getNodeCount(); j++) {
				cluster.nodeGlobalIDs[j] = Integer.parseInt(nodeList.get(j).toString());
			}

			// read model params
			Matrix c = new Jama.Matrix(
					convertDouble(sub.getList("params.c")), net.nodeCount);
			Matrix a = new Jama.Matrix(
					convertDouble(sub.getList("params.a")), net.nodeCount);
			Matrix sigma = new Jama.Matrix(convertDouble(sub
					.getList("params.sigma")), net.nodeCount);
			/*cluster.params.put("c", new Jama.Matrix(
					convertDouble(sub.getList("params.c")), net.nodeCount));
			cluster.params.put("a", new Jama.Matrix(
					convertDouble(sub.getList("params.a")), net.nodeCount));
			cluster.params.put("sigma", new Jama.Matrix(convertDouble(sub
					.getList("params.sigma")), net.nodeCount));
			*/
			
			cluster.setModel(new MVNModel(net.epsilon2, c, a, sigma));
			
			//cluster.init(net.epsilon1, net.epsilon2, allocate);
			// cluster.init();
			// conf.a.print(0, 7);
			// conf.sigma.print(0, 7);
			i++;
		}

		// init models etc
		//net.init();

		// allocate space for data; read into a DataProvider
		if (allocate) {
			globalDataProvider = new DataProvider(net.nodeCount, net.timeSteps);
			globalDataProvider.read(dataFile);
		}

		return net;
		
	}
	
	public static DataProvider getGlobalDataProvider() {
		return globalDataProvider;
	}
	
	public static Network getGlobalNetwork() {
		return globalNetwork;
	}
	
	private static double[] convertDouble(List list) {
		double[] d = new double[list.size()];
		for (int i = 0; i < list.size(); i++) {
			d[i] = Double.parseDouble(list.get(i).toString());
		}
		return d;
		// return t;
	}

//		try {
//			Class modelClass = Class.forName(model);
//			Model m = (Model) modelClass.newInstance();
//			//m.setNetworkConfiguration(this);
//			//m.startSimulation(cluster, e);
//		} catch (ClassNotFoundException e1) {
//			// TODO Auto-generated catch block
//			e1.printStackTrace();
//			// System.err.println("Class "+model+" not found!");
//		} catch (InstantiationException ie) {
//			// TODO Auto-generated catch block
//			ie.printStackTrace();
//		} catch (IllegalAccessException ile) {
//			// TODO Auto-generated catch block
//			ile.printStackTrace();
//		}

}
