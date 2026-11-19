package com.talkbot.plugin.manager;
import java.sql.*;import java.util.*;import java.util.logging.Level;import com.talkbot.plugin.TalkBotPlugin;
public final class BotRegistry {
 private final TalkBotPlugin plugin;private final DatabaseManager db;private final Set<String> bots=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
 public BotRegistry(TalkBotPlugin p,DatabaseManager d){plugin=p;db=d;reload();}
 public synchronized void reload(){bots.clear();try(PreparedStatement ps=db.connection().prepareStatement("SELECT username FROM registered_bots");ResultSet rs=ps.executeQuery()){while(rs.next())bots.add(rs.getString(1));}catch(SQLException e){plugin.getLogger().log(Level.SEVERE,"Could not load bots",e);}}
 public synchronized boolean registerBot(String n){if(!valid(n)||bots.contains(n))return false;try(PreparedStatement p=db.connection().prepareStatement("INSERT INTO registered_bots(username) VALUES(?)")){p.setString(1,n);p.executeUpdate();bots.add(n);return true;}catch(SQLException e){return false;}}
 public synchronized boolean unregisterBot(String n){try(PreparedStatement p=db.connection().prepareStatement("DELETE FROM registered_bots WHERE username=? COLLATE NOCASE")){p.setString(1,n);if(p.executeUpdate()==0)return false;bots.remove(n);return true;}catch(SQLException e){return false;}}
 public synchronized boolean isBot(String n){return bots.contains(n);}public synchronized Set<String> getRegisteredBots(){return Collections.unmodifiableSet(new TreeSet<>(bots));}
 public synchronized String getStyle(String n){try(PreparedStatement p=db.connection().prepareStatement("SELECT style FROM bot_styles WHERE username=? COLLATE NOCASE")){p.setString(1,n);try(ResultSet r=p.executeQuery()){if(r.next())return r.getString(1);}}catch(SQLException ignored){}return plugin.getConfig().getString("personality.default-style","chill");}
 public synchronized boolean setStyle(String n,String style){try(PreparedStatement p=db.connection().prepareStatement("INSERT INTO bot_styles(username,style) VALUES(?,?) ON CONFLICT(username) DO UPDATE SET style=excluded.style")){p.setString(1,n);p.setString(2,style);p.executeUpdate();return true;}catch(SQLException e){plugin.getLogger().warning("Could not save style for "+n+": "+e.getMessage());return false;}}
 private static boolean valid(String n){return n!=null&&n.matches("[A-Za-z0-9_]{3,16}");}
}