package com.sedmelluq.discord.lavaplayer.track.playback;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.TrackMarker;
import com.sedmelluq.discord.lavaplayer.track.TrackStateListener;

/**
 * Executor which handles track execution and all operations on playing tracks.
 */
public interface AudioTrackExecutor extends AudioFrameProvider {
  /**
   * @return The audio buffer of this executor.
   */
  AudioFrameBuffer getAudioBuffer();

  /**
   * Execute the track, which means that this thread will fill the frame buffer until the track finishes or is stopped.
   * @param listener Listener for track state events
   */
  void execute(TrackStateListener listener);

  /**
   * Stop playing the track, terminating the thread that is filling the frame buffer.
   * Subsequent playback requires a new executor.
   */
  void stop();

  /**
   * @return Timecode of the last played frame or in case a seek is in progress, the timecode of the frame being seeked to.
   */
  long getPosition();

  /**
   * Perform seek to the specified timecode.
   * @param timecode The timecode in milliseconds
   */
  void setPosition(long timecode);

  /**
   * @return Current state of the executor
   */
  AudioTrackState getState();

  /**
   * Set the track position marker. This will clear all existing markers.
   * @param marker Track position marker to set.
   */
  void setMarker(TrackMarker marker);

  /**
   * Adds a marker to the track.
   * Markers can be used to execute code when the track reaches a certain position.
   *
   * @param marker The marker to add.
   */
  void addMarker(TrackMarker marker);

  /**
   * Removes a marker from the track.
   *
   * @param marker The marker to remove.
   */
  void removeMarker(TrackMarker marker);

  /**
   * @return True if this track threw an exception before it provided any audio.
   */
  boolean failedBeforeLoad();
}
