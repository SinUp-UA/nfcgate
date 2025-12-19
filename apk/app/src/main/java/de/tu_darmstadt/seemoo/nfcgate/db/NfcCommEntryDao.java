package de.tu_darmstadt.seemoo.nfcgate.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface NfcCommEntryDao {
    @Insert
    void insert(NfcCommEntry log);

    @Query("SELECT * FROM NfcCommEntry ORDER BY entryId DESC LIMIT :limit")
    List<NfcCommEntry> getRecent(int limit);
}
