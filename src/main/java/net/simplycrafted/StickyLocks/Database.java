package net.simplycrafted.StickyLocks;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;

import java.sql.*;
import java.util.UUID;

/**
 * Copyright © Brian Ronald
 * 04/05/14
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
public class Database {
    // This class handles data storage for the StickyLocks plugin.
    // This class does not talk to players.
    // Only this class talks to the database.

    static private Connection db_conn;
    static StickyLocks stickylocks;

    public Database() {
        if (db_conn == null) {
            try {
                Class.forName("org.sqlite.JDBC");
                db_conn = DriverManager.getConnection("jdbc:sqlite:" + StickyLocks.getInstance().getDataFolder() + "/stickylocks.db");
            } catch (SQLException | ClassNotFoundException e) {
                StickyLocks.getInstance().getLogger().info(e.toString());
            }
        }
        stickylocks=StickyLocks.getInstance();
    }

    public void createTables() {
        Statement sql;
        PreparedStatement psql;
        try {
            sql = db_conn.createStatement();
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS player (uuid char(36) primary key,name text,notify tinyint not null default 0)");
            // Re-populate this every time we load. The config file is authoritative.
            sql.executeUpdate("DROP TABLE IF EXISTS protectable");
            sql.executeUpdate("CREATE TABLE protectable (material text primary key)");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS protected (x integer, y integer, z integer, world text, material text, owner char(36),PRIMARY KEY (x,y,z,world))");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS accessgroup (owner char(36),name text,member char(36),PRIMARY KEY (owner,name,member))");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS accesslist (owner char(36),member text,x integer,y integer,z integer,world text)");
            sql.close();
            // Load values for protectable blocks from the config
            for (String protectable : stickylocks.getConfig().getStringList("protectables")) {
                Material material = Material.getMaterial(protectable);
                if (material != null && material.isBlock()) {
                    psql= db_conn.prepareStatement("INSERT INTO protectable (material) VALUES (?)");
                    psql.setString(1, material.name());
                    psql.executeUpdate();
                    psql.close();
                } else {
                    stickylocks.getLogger().info(String.format("Warning: Configured item %s is not a Block type.",protectable));
                }
            }
            sql.close();
        } catch (SQLException e) {
            StickyLocks.getInstance().getLogger().info(e.toString());
        }
    }

    private Location getUnambiguousLocation(Block target) {
        int locX,locY,locZ;

        if (target.getState() instanceof Chest) {
            // Double chests have an ambiguous location. Get a unique location by
            // asking the chest's Inventory for the location of its DoubleChest.
            Chest chest = (Chest) target.getState();
            InventoryHolder ih = chest.getInventory().getHolder();
            if (ih instanceof DoubleChest) {
                DoubleChest dc = (DoubleChest) ih;
                locX = dc.getLocation().getBlockX();
                locY = dc.getLocation().getBlockY();
                locZ = dc.getLocation().getBlockZ();
            } else {
                locX = target.getLocation().getBlockX();
                locY = target.getLocation().getBlockY();
                locZ = target.getLocation().getBlockZ();
            }
        } else if (target.getType().name().equals("WOODEN_DOOR") && target.getRelative(BlockFace.DOWN).getType().name().equals("WOODEN_DOOR")) {
            // Doors have an ambiguous location, but we only need to check
            // the block below to disambiguate.
            locX = target.getLocation().getBlockX();
            locY = target.getLocation().getBlockY() - 1;
            locZ = target.getLocation().getBlockZ();
        } else {
            locX = target.getLocation().getBlockX();
            locY = target.getLocation().getBlockY();
            locZ = target.getLocation().getBlockZ();
        }
        return new Location(target.getWorld(),locX,locY,locZ);
    }

    public Protection getProtection (Block block) {
        PreparedStatement psql;
        ResultSet result;
        Protection returnValue;
        Location location = getUnambiguousLocation(block);
        try {
            psql = db_conn.prepareStatement("SELECT UUID,name " +
                    "FROM protectable " +
                    "LEFT JOIN protected " +
                    "ON protectable.material=protected.material " +
                    "AND x=?" +
                    "AND y=?" +
                    "AND z=?" +
                    "AND world=? " +
                    "LEFT JOIN player " +
                    "ON owner=UUID " +
                    "WHERE protectable.material LIKE ?");
            psql.setInt(1, location.getBlockX());
            psql.setInt(2, location.getBlockY());
            psql.setInt(3, location.getBlockZ());
            psql.setString(4, block.getLocation().getWorld().getName());
            psql.setString(5, block.getType().name());
            result = psql.executeQuery();
            if (result.next()) {
                String owner=result.getString(1);
                if (result.wasNull()) {
                    returnValue = new Protection(block.getType(), false, null, null);
                } else {
                    returnValue = new Protection(block.getType(), true, owner, result.getString(2));
                }
                result.close();
                psql.close();
                return returnValue;
            } else {
                result.close();
                psql.close();
                return new Protection(null, false, null, null);
            }
        } catch (SQLException e) {
            return new Protection(null, false, null, null);
        }
    }

    public void lockBlock(Block block, Player player) {
        PreparedStatement psql;
        Location location = getUnambiguousLocation(block);
        try {
            psql = db_conn.prepareStatement("REPLACE INTO protected (x,y,z,world,material,owner) VALUES (?,?,?,?,?,?)");
            psql.setInt(1, location.getBlockX());
            psql.setInt(2, location.getBlockY());
            psql.setInt(3, location.getBlockZ());
            psql.setString(4, location.getWorld().getName());
            psql.setString(5, block.getType().name());
            psql.setString(6, player.getUniqueId().toString());
            psql.executeUpdate();
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to insert record to lock block");
        }
    }

    public void unlockBlock(Block block) {
        PreparedStatement psql;
        Location location = getUnambiguousLocation(block);
        try {
            psql = db_conn.prepareStatement("DELETE FROM protected WHERE x=? AND y=? AND z=? AND world=?");
            psql.setInt(1, location.getBlockX());
            psql.setInt(2, location.getBlockY());
            psql.setInt(3, location.getBlockZ());
            psql.setString(4, location.getWorld().getName());
            psql.executeUpdate();
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to remove record to unlock block");
        }
    }

    public void shutdown() {
        try {
            db_conn.close();
        } catch (SQLException e) {
            StickyLocks.getInstance().getLogger().info(e.toString());
        }
    }

    public void addPlayer(Player player) {
        PreparedStatement psql;
        try {
            psql = db_conn.prepareStatement("REPLACE INTO player (uuid,name,notify) values (?,?,(SELECT notify FROM player WHERE uuid=?))");
            psql.setString(1, player.getUniqueId().toString());
            psql.setString(2, player.getName());
            psql.setString(3, player.getUniqueId().toString());
            psql.executeUpdate();
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to insert/replace newly joined player");
        }
    }

    public String addPlayerToGroup(UUID owner, String group, String member) {
        PreparedStatement psql;
        String memberUUID;
        ResultSet result;
        try {
            psql = db_conn.prepareStatement("SELECT uuid FROM player WHERE name LIKE ?");
            psql.setString(1,member);
            result = psql.executeQuery();
            if (result.next()) {
                memberUUID = result.getString(1);
            } else {
                return String.format("Player %s is not known", member);
            }
            result.close();
            psql = db_conn.prepareStatement("REPLACE INTO accessgroup (owner, name, member) VALUES (?,?,?)");
            psql.setString(1,owner.toString());
            psql.setString(2,group);
            psql.setString(3,memberUUID);
            psql.executeUpdate();
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to insert/replace group member");
        }
        return null;
    }

    public String removePlayerFromGroup(UUID owner, String group, String member) {
        PreparedStatement psql;
        String memberUUID;
        ResultSet result;
        try {
            psql = db_conn.prepareStatement("SELECT uuid FROM player WHERE name LIKE ?");
            psql.setString(1,member);
            result = psql.executeQuery();
            if (result.next()) {
                memberUUID = result.getString(1);
            } else {
                return String.format("Player %s is not known", member);
            }
            result.close();
            psql = db_conn.prepareStatement("DELETE FROM accessgroup WHERE owner=? AND name LIKE ? AND member=?");
            psql.setString(1,owner.toString());
            psql.setString(2,group);
            psql.setString(3,memberUUID);
            psql.executeUpdate();
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to remove group member");
        }
        return null;
    }

    public String renameGroup(UUID owner, String group, String newName) {
        PreparedStatement psql;
        try {
            psql = db_conn.prepareStatement("UPDATE accessgroup SET name=? WHERE owner=? AND name LIKE ?");
            psql.setString(1,newName);
            psql.setString(2,owner.toString());
            psql.setString(3,group);
            psql.executeUpdate();
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to remove group member");
        }
        return null;
    }

    public PlayerGroupList getGroup(UUID owner, String name) {
        PlayerGroupList groupList = new PlayerGroupList();
        PreparedStatement psql;
        ResultSet result;
        try {
            psql = db_conn.prepareStatement("SELECT player.name FROM accessgroup INNER JOIN player ON member=uuid WHERE owner=? AND accessgroup.name=? ORDER BY player.name");
            psql.setString(1, owner.toString());
            psql.setString(2, name);
            result = psql.executeQuery();
            while(result.next()) {
                result.getString(1);
                if(!result.wasNull())
                    groupList.insert(result.getString(1));
            }
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to retrieve group membership list");
        }
        return groupList;
    }

    public UUID getUUID(Location blockLocation) {
        Location location = getUnambiguousLocation(blockLocation.getBlock());
        PreparedStatement psql;
        ResultSet result;
        UUID returnVal = null;
        try {
            psql = db_conn.prepareStatement("SELECT owner " +
                    "FROM protectable " +
                    "INNER JOIN protected " +
                    "ON protectable.material=protected.material " +
                    "AND x=?" +
                    "AND y=?" +
                    "AND z=?" +
                    "AND world=?");
            psql.setInt(1, location.getBlockX());
            psql.setInt(2, location.getBlockY());
            psql.setInt(3, location.getBlockZ());
            psql.setString(4, location.getWorld().getName());
            result = psql.executeQuery();
            if (result.next()) {
                result.getString(1);
                if (result.wasNull()) {
                    returnVal = null;
                } else {
                    returnVal = UUID.fromString(result.getString(1));
                }
            }
            result.close();
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to retrieve UUID from block");
        }
        return returnVal;
    }

    public PlayerGroupList listGroups(UUID owner) {
        PlayerGroupList groupList = new PlayerGroupList();
        PreparedStatement psql;
        ResultSet result;
        try {
            psql = db_conn.prepareStatement("SELECT name FROM accessgroup WHERE owner=? GROUP BY name ORDER BY name");
            psql.setString(1, owner.toString());
            result = psql.executeQuery();
            while(result.next()) {
                result.getString(1);
                if(!result.wasNull())
                    groupList.insert(result.getString(1));
            }
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to retrieve group list");
        }
        return groupList;
    }

    public String getName(UUID player) {
        PreparedStatement psql;
        ResultSet result;
        String returnVal = null;
        try {
            psql = db_conn.prepareStatement("SELECT name FROM player WHERE uuid LIKE ?");
            psql.setString(1,player.toString());
            result = psql.executeQuery();
            if (result.next()) {
                returnVal = result.getString(1);
            }
            result.close();
            psql.close();
        } catch (SQLException e) {
            stickylocks.getLogger().info("Failed to get UUID from name");
        }
        return returnVal;
    }
}