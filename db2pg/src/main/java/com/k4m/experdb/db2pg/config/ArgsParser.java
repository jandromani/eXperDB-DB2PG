package com.k4m.experdb.db2pg.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.k4m.experdb.db2pg.common.Constant;
import com.k4m.experdb.db2pg.common.LogUtils;
import com.k4m.experdb.db2pg.common.RunCommandExec;
import com.k4m.experdb.db2pg.rebuild.RebuildSummary;
import com.k4m.experdb.db2pg.sample.SampleFileLoader;
import com.k4m.experdb.db2pg.unload.UnloadSummary;

public class ArgsParser {
	Options options;
	CommandLineParser parser;
	HelpFormatter formatter;
	CommandLine cmd;

	public ArgsParser() {
		options = new Options();
		parser = new DefaultParser();
		formatter = new HelpFormatter();
		init();
	}

	private void init() {
		Option option = null;
		option = new Option("c", "config", true, "config file path");
		option.setRequired(false);
		options.addOption(option);

		option = new Option("x", "query-file", true, "Query XML file path");
		option.setRequired(false);
		options.addOption(option);
		
		option = new Option("h", "help", false, "db2pg help");
		option.setRequired(false);
		options.addOption(option);
		
		option = new Option("M", "make-templates", false, "make configuration sample file");
		option.setRequired(false);
		options.addOption(option);
		
		option = new Option(null, "rebuild-summary", true, "rebuild log files summary");
		option.setRequired(false);
		options.addOption(option);
		option = new Option(null, "unload-summary", true, "unload log file summary");
		option.setRequired(false);
		options.addOption(option);
		option = new Option(null, "src-include-data-export", false, "data export from source database");
		option.setRequired(false);
		options.addOption(option);
		option = new Option(null, "src-ddl-export", false, "ddl export from source database");
		option.setRequired(false);
		options.addOption(option);
		option = new Option(null, "tar-constraint-ddl", false, "constraint export from target database");
		option.setRequired(false);
		options.addOption(option);
		option = new Option(null, "iot-start", false, "Fluentd Service Start");
		option.setRequired(false);
		options.addOption(option);
		option = new Option(null, "iot-stop", false, "Fluentd Service Stop");
		option.setRequired(false);
		options.addOption(option);
		option = new Option(null, "iot-status", false, "Fluentd Service Status");
		option.setRequired(false);
		options.addOption(option);
	}

	public void parse(String[] args) {
		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			LogUtils.error(e.getMessage(), ArgsParser.class);
			System.out.println(e.getMessage());
			formatter.printHelp("eXperDB-DB2PG", options);
			System.exit(Constant.ERR_CD.METHOD_NOT_ALLOWD_ERR);
		}
		boolean summarized = false;
		if (cmd.hasOption("make-templates")) {
			try {
				InputStream is = SampleFileLoader.getResourceInputStream("com/k4m/experdb/db2pg/sample/db2pg.config");
				OutputStream out = new FileOutputStream(new File("db2pg.config"));

				int r = -1;
				while ((r = is.read()) != -1) {
					out.write(r);
				}

				is.close();
				out.close();

				is = SampleFileLoader.getResourceInputStream("com/k4m/experdb/db2pg/sample/queries.xml");
				out = new FileOutputStream(new File("queries.xml"));

				r = -1;
				while ((r = is.read()) != -1) {
					out.write(r);
				}

				is.close();
				out.close();

			} catch (Exception e) {
				LogUtils.error(e.getMessage(), ArgsParser.class);
				System.exit(Constant.ERR_CD.UNKNOWN_ERR);
			}
			System.exit(Constant.ERR_CD.SUCCESS);
		}
		if (cmd.hasOption("rebuild-summary")) {
			String[] rebuildLogs = cmd.getOptionValue("rebuild-summary").split(",");

			for (int i = 0; i < rebuildLogs.length; i++) {
				rebuildLogs[i] = rebuildLogs[i].trim();
			}
			(new RebuildSummary(".", rebuildLogs)).run();
			summarized = true;
		}
		if (cmd.hasOption("unload-summary")) {
			String unloadLog = cmd.getOptionValue("unload-summary");
			(new UnloadSummary(".", unloadLog)).run();
			summarized = true;
		}

		if (summarized) {
			System.exit(Constant.ERR_CD.SUCCESS);
		}
		if (cmd.hasOption("config")) {
			ConfigInfo.Loader.load(cmd.getOptionValue("config"));
		} else {
			File defaultConfig = new File("db2pg.config");
			if(defaultConfig.exists()) {
				ConfigInfo.Loader.load(defaultConfig.getAbsolutePath());
			}else{
				System.out.println("Cannot Find db2pg.config File !!!");
				System.out.println(System.getProperty("user.dir"));
				System.exit(Constant.ERR_CD.METHOD_NOT_ALLOWD_ERR);
			}
		}
		
		if (cmd.hasOption("query-file")) {
			ConfigInfo.SRC_FILE_QUERY_DIR_PATH = cmd.getOptionValue("query-file");
		}
		if (cmd.hasOption("src-include-data-export")) {
			ConfigInfo.SRC_INCLUDE_DATA_EXPORT = true;
		}
		if (cmd.hasOption("src-ddl-export")) {
			ConfigInfo.SRC_DDL_EXPORT = true;
		}
		if (cmd.hasOption("tar-constraint-ddl")) {
			ConfigInfo.TAR_CONSTRAINT_DDL = true;
		}
		if (cmd.hasOption("help")) {			
			formatter.printHelp("DB2PG", options);
			System.exit(Constant.ERR_CD.METHOD_NOT_ALLOWD_ERR);
		}
		if (cmd.hasOption("iot-start")) {			
			String strCmd = "./td-agent.sh start";
			HashMap<String, String> hp = execIotService(strCmd);
			
			String result = (String) hp.get("result");
			String msg = (String) hp.get("msg");
			
			System.out.println(msg);
			
			if(result.equals("success")) {
				System.exit(Constant.ERR_CD.SUCCESS);
			} else {
				System.exit(Constant.ERR_CD.UNKNOWN_ERR);
			}
		}
		
		if (cmd.hasOption("iot-stop")) {			
			String strCmd = "./td-agent.sh stop";
			HashMap<String, String> hp = execIotService(strCmd);
			
			String result = (String) hp.get("result");
			String msg = (String) hp.get("msg");
			
			System.out.println(msg);
			
			if(result.equals("success")) {
				System.exit(Constant.ERR_CD.SUCCESS);
			} else {
				System.exit(Constant.ERR_CD.UNKNOWN_ERR);
			}
		}
		
		if (cmd.hasOption("iot-status")) {			
			String strCmd = "./td-agent.sh status";
			HashMap<String, String> hp = execIotService(strCmd);
			
			String result = (String) hp.get("result");
			String msg = (String) hp.get("msg");
			
			System.out.println(msg);
			
			if(result.equals("success")) {
				System.exit(Constant.ERR_CD.SUCCESS);
			} else {
				System.exit(Constant.ERR_CD.UNKNOWN_ERR);
			}
		}
	}
	
	private HashMap<String, String> execIotService(String strCmd) {
		HashMap<String, String> hp = new HashMap<String, String>();
		
		RunCommandExec r = new RunCommandExec(strCmd);
		r.start();
		try {
			r.join();
		} catch (InterruptedException ie) {
			ie.printStackTrace();
		}
		String retVal = r.call();
		String strResultMessge = r.getMessage();
		
		hp.put("result", retVal);
		hp.put("msg", strResultMessge);
		
		return hp;
	}

//	public static void main(String[] args) throws Exception {
//		(new ArgsParser()).parse(args);
//	}

}
