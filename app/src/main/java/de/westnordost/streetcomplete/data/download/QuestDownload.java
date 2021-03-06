package de.westnordost.streetcomplete.data.download;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Provider;

import de.westnordost.streetcomplete.ApplicationConstants;
import de.westnordost.streetcomplete.Prefs;
import de.westnordost.streetcomplete.data.QuestType;
import de.westnordost.streetcomplete.data.QuestTypes;
import de.westnordost.streetcomplete.data.VisibleQuestListener;
import de.westnordost.streetcomplete.data.osm.OverpassQuestType;
import de.westnordost.streetcomplete.data.osm.download.OsmQuestDownload;
import de.westnordost.streetcomplete.data.osmnotes.OsmNoteQuest;
import de.westnordost.streetcomplete.data.osmnotes.OsmNoteQuestDao;
import de.westnordost.streetcomplete.data.osmnotes.OsmNotesDownload;
import de.westnordost.streetcomplete.data.tiles.DownloadedTilesDao;
import de.westnordost.streetcomplete.util.SlippyMapMath;
import de.westnordost.osmapi.map.data.BoundingBox;
import de.westnordost.osmapi.map.data.LatLon;

public class QuestDownload
{
	private static final String TAG = "QuestDownload";

	private final Provider<OsmNotesDownload> notesDownloadProvider;
	private final Provider<OsmQuestDownload> questDownloadProvider;
	private final QuestTypes questTypeList;
	private final SharedPreferences prefs;
	private final DownloadedTilesDao downloadedTilesDao;
	private final OsmNoteQuestDao osmNoteQuestDb;

	private Rect tiles;
	private Integer maxQuestTypes;
	private AtomicBoolean cancelState;
	private boolean isPriority;

	// listeners
	private VisibleQuestListener questListener;
	private QuestDownloadProgressListener progressListener;

	// state
	private int downloadedQuestTypes = 0;
	private int totalQuestTypes;
	private int visibleQuests = 0;
	private boolean finished = false;

	@Inject public QuestDownload(Provider<OsmNotesDownload> notesDownloadProvider,
								 Provider<OsmQuestDownload> questDownloadProvider,
								 DownloadedTilesDao downloadedTilesDao,
								 OsmNoteQuestDao osmNoteQuestDb,
								 QuestTypes questTypeList, SharedPreferences prefs)
	{
		this.notesDownloadProvider = notesDownloadProvider;
		this.questDownloadProvider = questDownloadProvider;
		this.downloadedTilesDao = downloadedTilesDao;
		this.osmNoteQuestDb = osmNoteQuestDb;
		this.questTypeList = questTypeList;
		this.prefs = prefs;
	}

	public void setQuestTypeListener(VisibleQuestListener questListener)
	{
		this.questListener = questListener;
	}

	public void setProgressListener(QuestDownloadProgressListener progressListener)
	{
		this.progressListener = progressListener;
	}

	public void init(Rect tiles, Integer maxQuestTypes, boolean isPriority,
					 AtomicBoolean cancel)
	{
		this.tiles = tiles;
		this.maxQuestTypes = maxQuestTypes;
		this.isPriority = isPriority;
		this.cancelState = cancel;
	}

	public void download()
	{
		if(cancelState.get()) return;

		List<QuestType> questTypes = getQuestTypesToDownload();
		if(questTypes.isEmpty())
		{
			finished = true;
			progressListener.onNotStarted();
			return;
		}

		totalQuestTypes = questTypes.size();

		BoundingBox bbox = SlippyMapMath.asBoundingBox(tiles, ApplicationConstants.QUEST_TILE_ZOOM);

		try
		{
			Log.i(TAG, "(" + bbox.getAsLeftBottomRightTopString() + ") Starting");
			progressListener.onStarted();

			Set<LatLon> notesPositions;
			if(questTypes.contains(OsmNoteQuest.type))
			{
				notesPositions = downloadNotes();
			}
			else
			{
				notesPositions = getNotePositionsFromDb();
			}

			downloadQuestTypes(questTypes, notesPositions);
		}
		finally
		{
			finished = true;
			progressListener.onFinished();
			Log.i(TAG, "(" + bbox.getAsLeftBottomRightTopString() + ") Finished");
		}
	}

	private List<QuestType> getQuestTypesToDownload()
	{
		List<QuestType> result = new ArrayList<>(questTypeList.getQuestTypesSortedByImportance());
		result.add(0, OsmNoteQuest.type);

		long questExpirationTime = Integer.parseInt(prefs.getString(Prefs.QUESTS_EXPIRATION_TIME_IN_MIN, "0")) * 1000 * 60;
		long ignoreOlderThan = Math.max(0,System.currentTimeMillis() - questExpirationTime);
		List<String> alreadyDownloadedNames = downloadedTilesDao.getQuestTypeNames(tiles, ignoreOlderThan);
		if(!alreadyDownloadedNames.isEmpty())
		{
			Set<QuestType> alreadyDownloaded = new HashSet<>(alreadyDownloadedNames.size());
			for (String questTypeName : alreadyDownloadedNames)
			{
				if(questTypeName.equals(OsmNoteQuest.type.getClass().getSimpleName()))
				{
					alreadyDownloaded.add(OsmNoteQuest.type);
				}
				else
				{
					alreadyDownloaded.add(questTypeList.forName(questTypeName));
				}
			}
			result.removeAll(alreadyDownloaded);

			Log.i(TAG, "Not downloading quest types because they are in local storage already: " +
					Arrays.toString(alreadyDownloadedNames.toArray()));
		}

		return result;
	}

	private Set<LatLon> getNotePositionsFromDb()
	{
		BoundingBox bbox = SlippyMapMath.asBoundingBox(tiles, ApplicationConstants.QUEST_TILE_ZOOM);
		List<LatLon> positionList = osmNoteQuestDb.getAllPositions(bbox);
		Set<LatLon> positions = new HashSet<>(positionList.size());
		for (LatLon pos : positionList)
		{
			positions.add(pos);
		}
		return positions;
	}

	private Set<LatLon> downloadNotes()
	{
		OsmNotesDownload notesDownload = notesDownloadProvider.get();
		notesDownload.setQuestListener(questListener);

		Long userId = prefs.getLong(Prefs.OSM_USER_ID, -1);
		if(userId == -1) userId = null;

		int maxNotes = 10000;
		Set<LatLon> result = notesDownload.download(tiles, userId, maxNotes);
		downloadedQuestTypes++;
		dispatchProgress();
		return result;
	}

	private int downloadQuestTypes(List<QuestType> questTypes, Set<LatLon> notesPositions)
	{
		int visibleQuests = 0;
		for (QuestType questType : questTypes)
		{
			if (!(questType instanceof OverpassQuestType)) continue;
			if (cancelState.get()) break;
			if (maxQuestTypes != null && downloadedQuestTypes >= maxQuestTypes) break;

			OsmQuestDownload questDownload = questDownloadProvider.get();
			questDownload.setQuestListener(questListener);

			visibleQuests += questDownload.download((OverpassQuestType) questType, tiles, notesPositions);

			downloadedQuestTypes++;
			dispatchProgress();
		}
		return visibleQuests;
	}

	public float getProgress()
	{
		int max = totalQuestTypes;
		if(maxQuestTypes != null) max = Math.min(maxQuestTypes, totalQuestTypes);
		return Math.min(1f, (float) downloadedQuestTypes / max);
	}

	public boolean isPriority()
	{
		return isPriority;
	}

	public boolean isFinished()
	{
		return finished;
	}

	private void dispatchProgress()
	{
		progressListener.onProgress(getProgress());
	}
}
