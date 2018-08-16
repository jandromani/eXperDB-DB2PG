package com.k4m.experdb.db2pg.convert;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.MessageFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.regex.Pattern;

import com.k4m.experdb.db2pg.common.Constant;
import com.k4m.experdb.db2pg.common.LogUtils;
import com.k4m.experdb.db2pg.config.ConfigInfo;
import com.k4m.experdb.db2pg.convert.db.ConvertDBUtils;
import com.k4m.experdb.db2pg.convert.exception.NotSupportDatabaseTypeException;
import com.k4m.experdb.db2pg.convert.make.PgDDLMaker;
import com.k4m.experdb.db2pg.convert.map.ConvertMapper;
import com.k4m.experdb.db2pg.convert.map.MySqlConvertMapper;
import com.k4m.experdb.db2pg.convert.table.Column;
import com.k4m.experdb.db2pg.convert.table.Table;
import com.k4m.experdb.db2pg.convert.type.DDL_TYPE;
import com.k4m.experdb.db2pg.db.DBCPPoolManager;
import com.k4m.experdb.db2pg.db.DBUtils;
import com.k4m.experdb.db2pg.db.datastructure.DBConfigInfo;
import com.k4m.experdb.db2pg.db.datastructure.exception.DBTypeNotFoundException;

public class DDLConverter {
	protected ConvertMapper<?> convertMapper;
	protected PriorityBlockingQueue<DDLString> tableQueue = new PriorityBlockingQueue<DDLString>(5, DDLString.getComparator()),
			indexQueue = new PriorityBlockingQueue<DDLString>(5, DDLString.getComparator()),
			constraintsQueue = new PriorityBlockingQueue<DDLString>(5, DDLString.getComparator());
	protected DBConfigInfo dbConfigInfo;
	protected String outputDirectory = ConfigInfo.OUTPUT_DIRECTORY + "ddl/";
	protected List<String> tableNameList = null, excludes = null;

	public static DDLConverter getInstance() throws Exception {
		DDLConverter ddlConverter = new DDLConverter();
		switch (ConfigInfo.SRC_DB_TYPE) {
		case Constant.DB_TYPE.MYSQL:
			ddlConverter.convertMapper = ConvertMapper.makeConvertMapper(MySqlConvertMapper.class);
			break;
		case Constant.DB_TYPE.ORA:
			ddlConverter.convertMapper = null;
			break;
		case Constant.DB_TYPE.MSS:
			ddlConverter.convertMapper = null;
			break;
		default:
			throw new NotSupportDatabaseTypeException(ConfigInfo.SRC_DB_TYPE);
		}
		return ddlConverter;
	}

	public DDLConverter() throws Exception {
		File dir = new File(outputDirectory);
		if (!dir.exists()) {
			LogUtils.info(String.format("%s directory is not existed.", dir.getPath()), DDLConverter.class);
			if (dir.mkdirs()) {
				LogUtils.info(String.format("Success to create %s directory.", dir.getPath()), DDLConverter.class);
			} else {
				LogUtils.error(String.format("Failed to create %s directory.", dir.getPath()), DDLConverter.class);
				System.exit(Constant.ERR_CD.FAILED_CREATE_DIR_ERR);
			}
		}
		this.dbConfigInfo = new DBConfigInfo();
		dbConfigInfo.SERVERIP = ConfigInfo.SRC_HOST;
		dbConfigInfo.PORT = String.valueOf(ConfigInfo.SRC_PORT);
		dbConfigInfo.USERID = ConfigInfo.SRC_USER;
		dbConfigInfo.DB_PW = ConfigInfo.SRC_PASSWORD;
		dbConfigInfo.DBNAME = ConfigInfo.SRC_DATABASE;
		dbConfigInfo.DB_TYPE = ConfigInfo.SRC_DB_TYPE;
		dbConfigInfo.CHARSET = ConfigInfo.SRC_DB_CHARSET;
		dbConfigInfo.SCHEMA_NAME = ConfigInfo.SRC_SCHEMA;
		if (dbConfigInfo.SCHEMA_NAME == null && dbConfigInfo.SCHEMA_NAME.trim().equals(""))
			dbConfigInfo.SCHEMA_NAME = dbConfigInfo.USERID;
		DBCPPoolManager.setupDriver(dbConfigInfo, Constant.POOLNAME.SOURCE_DDL.name(), 1);
		tableNameList = ConfigInfo.SRC_ALLOW_TABLES;
		if (tableNameList == null) {
			tableNameList = DBUtils.getTableNames(ConfigInfo.TABLE_ONLY, Constant.POOLNAME.SOURCE_DDL.name(),
					dbConfigInfo);
		}
		if (excludes != null) {
			for (int eidx = 0; eidx < excludes.size(); eidx++) {
				String exclude = excludes.get(eidx);
				for (String tableName : tableNameList) {
					if (exclude.equals(tableName)) {
						tableNameList.remove(exclude);
						break;
					}
				}
			}
		}
	}

	public void start() throws DBTypeNotFoundException, IOException, NotSupportDatabaseTypeException {
		DDLString ddlStrVO = null;
		List<Table> tables = ConvertDBUtils.getTableInform(tableNameList, true, Constant.POOLNAME.SOURCE_DDL.name(),
				dbConfigInfo);
		
		PgDDLMaker<Table> maker = new PgDDLMaker<Table>(DDL_TYPE.CREATE);
		Queue<DDLString> ddlQueue = new LinkedBlockingQueue<DDLString>();
		for (Table table : tables) {
			ConvertDBUtils.setColumnInform(table, Constant.POOLNAME.SOURCE_DDL.name(), dbConfigInfo);
			ConvertDBUtils.setConstraintInform(table, Constant.POOLNAME.SOURCE_DDL.name(), dbConfigInfo);
			ConvertDBUtils.setKeyInform(table, Constant.POOLNAME.SOURCE_DDL.name(), dbConfigInfo);

			tableConvert(table);

			maker.setting(table);
			ddlQueue.clear();
			ddlQueue.addAll(maker.make());

			while ((ddlStrVO = ddlQueue.poll()) != null) {
				if (ddlStrVO.getDDLType() == DDL_TYPE.CREATE) {
					switch (ddlStrVO.getCommandType()) {
					case TYPE: case TABLE: case COMMENT: case SEQUENCE:
						tableQueue.add(ddlStrVO);
						break;
					case FOREIGN_KEY: case PRIMARY_KEY:
						constraintsQueue.add(ddlStrVO); 
						break;
					case INDEX:
						indexQueue.add(ddlStrVO);
						break;
					default:
						break;
					}
				}
			}
			ddlQueue.clear();
		}

		writeToFile();
	}

	private void writeToFile() throws IOException {
		List<String> alertComments = new LinkedList<String>();
		DDLString ddlStrVO = null;
		
		ByteBuffer fileBuffer = ByteBuffer.allocateDirect(ConfigInfo.BUFFER_SIZE);
		FileChannel fch = null;
		File tableSqlFile = new File(outputDirectory + "/" + dbConfigInfo.DBNAME + "_table.sql");
		FileOutputStream fos = new FileOutputStream(tableSqlFile);
		fch = fos.getChannel();

		while ((ddlStrVO = tableQueue.poll()) != null) {
			if (ddlStrVO.getAlertComments() != null) {
				for (String alertComment : ddlStrVO.getAlertComments()) {
					fileBuffer.put(alertComment.getBytes(ConfigInfo.TAR_DB_CHARSET));
					alertComments.add(alertComment);
					fileBuffer.put("\n".getBytes(ConfigInfo.TAR_DB_CHARSET));
				}
			}
			fileBuffer.put(ddlStrVO.toString().getBytes(ConfigInfo.TAR_DB_CHARSET));
			fileBuffer.put("\n".getBytes(ConfigInfo.TAR_DB_CHARSET));
			fileBuffer.flip();
			fch.write(fileBuffer);
			fileBuffer.clear();
		}
		fch.close();
		fos.close();

		File constraintsSqlFile = new File(outputDirectory + "/" + dbConfigInfo.DBNAME + "_constraints.sql");
		fos = new FileOutputStream(constraintsSqlFile);
		fch = fos.getChannel();
		while ((ddlStrVO = constraintsQueue.poll()) != null) {
			fileBuffer.put(ddlStrVO.toString().getBytes(ConfigInfo.TAR_DB_CHARSET));
			fileBuffer.put("\n".getBytes(ConfigInfo.TAR_DB_CHARSET));
			fileBuffer.flip();
			fch.write(fileBuffer);
			fileBuffer.clear();
		}
		fch.close();
		fos.close();

		File indexSqlFile = new File(outputDirectory + "/" + dbConfigInfo.DBNAME + "_index.sql");
		fos = new FileOutputStream(indexSqlFile);
		fch = fos.getChannel();
		while ((ddlStrVO = indexQueue.poll()) != null) {
			fileBuffer.put(ddlStrVO.toString().getBytes(ConfigInfo.TAR_DB_CHARSET));
			fileBuffer.put("\n".getBytes(ConfigInfo.TAR_DB_CHARSET));
			fileBuffer.flip();
			fch.write(fileBuffer);
			fileBuffer.clear();
		}
		fch.close();
		fos.close();
		if (!alertComments.isEmpty()) {
			File alertFile = new File(outputDirectory + "/" + dbConfigInfo.DBNAME + "_alert.log");
			fos = new FileOutputStream(alertFile);
			fch = fos.getChannel();
			for (String alertComment : alertComments) {
				System.out.println(alertComment);
				fileBuffer.put(alertComment.getBytes(ConfigInfo.TAR_DB_CHARSET));
				fileBuffer.put("\n".getBytes(ConfigInfo.TAR_DB_CHARSET));
				fileBuffer.flip();
				fch.write(fileBuffer);
				fileBuffer.clear();
			}
			fch.close();
			fos.close();
		}
	}
	
	private void tableConvert(Table table) throws NotSupportDatabaseTypeException {
		if (convertMapper != null) {
			switch (ConfigInfo.SRC_DB_TYPE) {
			case Constant.DB_TYPE.MYSQL:
				mysqlTableConvert(table);
				break;
			case Constant.DB_TYPE.ORA:
				break;
			case Constant.DB_TYPE.MSS:
				break;
			default:
				throw new NotSupportDatabaseTypeException(ConfigInfo.SRC_DB_TYPE);
			}
		}
	}
	
	private void mysqlTableConvert(Table table) throws NotSupportDatabaseTypeException {
		for (Column column : table.getColumns()) {
			for (ConvertObject convertVO : convertMapper.getPatternList()) {
				if (convertVO.getPattern().matcher(column.getType()).find()) {
					if (convertVO.getToValue().equals("DECIMAL")) {
						column.setType(String.format("%s(%d,%d)", convertVO.getToValue(),
								column.getNumericPrecision(), column.getNumericScale()));
					} else if (convertVO.getToValue().equals("BOOLEAN")) {
						if (column.getDefaultValue() != null) {
							if (column.getDefaultValue().equals("1")) {
								column.setDefaultValue("TRUE");
							} else if (column.getDefaultValue().equals("0")) {
								column.setDefaultValue("FALSE");
							}
						}
						column.setType(convertVO.getToValue());
					} else if (convertVO.getToValue().equals("DATE")) {
						String yearRedix = "^(?i)YEAR\\s*\\(?[0-9]*\\)?$";
						Pattern yearPattern = Pattern.compile(yearRedix);
						if (yearPattern.matcher(column.getType()).matches()) {
							table.alertComments().add(MessageFormat.format(
									"/*" + "\n * MySQL Year Type has been changed to PostgresQL Date Type."
											+ "\n * Column : {0}.{1}.{2}" + "\n */",
									table.getSchemaName(), table.getName(), column.getName()));
						}
						column.setType(convertVO.getToValue());
					} else {
						column.setType(convertVO.getToValue());
					}
					break;
				}
			}
			String onUptRedix = "^(?i)on update \\w*$";
			Pattern onUptPattern = Pattern.compile(onUptRedix);
			if (onUptPattern.matcher(column.getExtra()).matches()) {
				table.alertComments().add(MessageFormat.format("/*"
						+ "\n * MySQL {0}.{1} table''s {2} column has been included on update constraint on table."
						+ "\n * But, PostgresQL''s column isn''t supported on update constraint."
						+ "\n * So, need you will make trigger action on this table."
						+ "\n * Column : {0}.{1}.{2}" + "\n */", table.getSchemaName(), table.getName(),
						column.getName()));
			}
		}
	}
}