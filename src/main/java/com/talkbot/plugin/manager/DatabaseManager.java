package com.talkbot.plugin.manager;
import com.talkbot.plugin.TalkBotPlugin;
import java.io.File;import java.sql.*;import java.util.logging.Level;
public final class DatabaseManager {
 private final TalkBotPlugin plugin; private Connection connection;
 public DatabaseManager(TalkBotPlugin plugin){this.plugin=plugin;}
 public synchronized boolean open(){try{if(!plugin.getDataFolder().exists()&&!plugin.getDataFolder().mkdirs())throw new SQLException("Could not create data folder");String f=plugin.getConfig().getString("database.filename","talkbot.db");File db=new File(plugin.getDataFolder(),f);connection=DriverManager.getConnection("jdbc:sqlite:"+db.getAbsolutePath());try(Statement s=connection.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS registered_bots(username TEXT PRIMARY KEY NOT NULL)");s.executeUpdate("CREATE TABLE IF NOT EXISTS bot_styles(username TEXT PRIMARY KEY NOT NULL, style TEXT NOT NULL)");}return true;}catch(SQLException e){plugin.getLogger().log(Level.SEVERE,"SQLite initialization failed",e);close();return false;}}
 public synchronized Connection connection()throws SQLException{if(connection==null||connection.isClosed())throw new SQLException("Database unavailable");return connection;}
 public synchronized void close(){if(connection!=null)try{connection.close();}catch(SQLException e){plugin.getLogger().log(Level.WARNING,"SQLite close failed",e);}finally{connection=null;}}
}