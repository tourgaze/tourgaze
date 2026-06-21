/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.garmin.fit.Decode;
import com.garmin.fit.FitRuntimeException;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesgListener;
import com.garmin.fit.SessionMesgListener;
import com.garmin.fit.Sport;

/**
 * FIT file parser backed by the official Garmin FIT Java SDK.
 * Extracts GPS track points from Record messages and summary metadata
 * from the (first) Session message.
 */
@Component
public class FitParser implements TrackFileParser {

	private static final double SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0;

	@Override
	public boolean supports(String format) {
		return "fit".equalsIgnoreCase(format);
	}

	@Override
	public ParseResult parse(byte[] data) {
		if (data == null || data.length < 14) {
			throw new IllegalArgumentException("File too small to be a valid FIT file");
		}

		List<TrackPoint> points = new ArrayList<>();
		SessionData session = new SessionData();

		Decode decode = new Decode();
		MesgBroadcaster broadcaster = new MesgBroadcaster(decode);

		broadcaster.addListener((RecordMesgListener) mesg -> {
			Integer latSc = mesg.getPositionLat();
			Integer lonSc = mesg.getPositionLong();
			if (latSc == null || lonSc == null)
				return;

			Instant time = mesg.getTimestamp() != null
					? mesg.getTimestamp().getDate().toInstant()
					: null;
			Double altM = mesg.getAltitude() != null ? mesg.getAltitude().doubleValue() : null;
			Integer hr = mesg.getHeartRate() != null ? mesg.getHeartRate().intValue() : null;
			Double speedMs = mesg.getSpeed() != null ? mesg.getSpeed().doubleValue() : null;

			points.add(new TrackPoint(
					time,
					latSc * SEMICIRCLE_TO_DEG,
					lonSc * SEMICIRCLE_TO_DEG,
					altM,
					hr,
					speedMs));
		});

		broadcaster.addListener((SessionMesgListener) mesg -> {
			if (session.seen)
				return; // keep first session only
			session.seen = true;

			Sport sport = mesg.getSport();
			session.sport = sport != null ? decodeSport(sport) : null;
			session.startTime = mesg.getStartTime() != null
					? mesg.getStartTime().getDate().toInstant()
					: null;
			session.distanceM = mesg.getTotalDistance() != null
					? mesg.getTotalDistance().doubleValue()
					: null;
			session.ascentM = mesg.getTotalAscent() != null
					? mesg.getTotalAscent().doubleValue()
					: null;
			session.elapsedS = mesg.getTotalElapsedTime() != null
					? Math.round(mesg.getTotalElapsedTime())
					: null;
			session.movingS = mesg.getTotalTimerTime() != null
					? Math.round(mesg.getTotalTimerTime())
					: null;
			session.avgHr = mesg.getAvgHeartRate() != null ? mesg.getAvgHeartRate().intValue() : null;
			session.maxHr = mesg.getMaxHeartRate() != null ? mesg.getMaxHeartRate().intValue() : null;
			session.avgSpeedMs = mesg.getAvgSpeed() != null ? mesg.getAvgSpeed().doubleValue() : null;
			session.maxSpeedMs = mesg.getMaxSpeed() != null ? mesg.getMaxSpeed().doubleValue() : null;
			session.avgCadence = mesg.getAvgCadence() != null ? mesg.getAvgCadence().intValue() : null;
			session.maxCadence = mesg.getMaxCadence() != null ? mesg.getMaxCadence().intValue() : null;
			session.avgPowerW = mesg.getAvgPower() != null ? mesg.getAvgPower().intValue() : null;
			session.maxPowerW = mesg.getMaxPower() != null ? mesg.getMaxPower().intValue() : null;
		});

		try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
			broadcaster.run(in);
		} catch (FitRuntimeException e) {
			throw new IllegalArgumentException("Failed to parse FIT file: " + e.getMessage(), e);
		} catch (Exception e) {
			throw new IllegalArgumentException("I/O error reading FIT bytes: " + e.getMessage(), e);
		}

		Instant startTime = session.startTime != null
				? session.startTime
				: (points.isEmpty() ? null : points.get(0).time());
		Instant endTime = points.isEmpty() ? null : points.get(points.size() - 1).time();
		if (endTime == null && startTime != null && session.elapsedS != null) {
			endTime = startTime.plusSeconds(session.elapsedS);
		}

		Integer durationS = session.elapsedS != null
				? session.elapsedS
				: (startTime != null && endTime != null
						? (int) (endTime.getEpochSecond() - startTime.getEpochSecond())
						: null);

		return ParseResult.builder()
				.points(points)
				.sport(session.sport)
				.distanceM(session.distanceM)
				.ascentM(session.ascentM)
				.startTime(startTime)
				.endTime(endTime)
				.durationS(durationS)
				.movingTimeS(session.movingS)
				.avgHr(session.avgHr)
				.maxHr(session.maxHr)
				.avgSpeedMs(session.avgSpeedMs)
				.maxSpeedMs(session.maxSpeedMs)
				.avgCadence(session.avgCadence)
				.maxCadence(session.maxCadence)
				.avgPowerW(session.avgPowerW)
				.maxPowerW(session.maxPowerW)
				.build();
	}

	private static String decodeSport(Sport sport) {
		return switch (sport) {
			case GENERIC -> "generic";
			case RUNNING -> "running";
			case CYCLING -> "cycling";
			case SWIMMING -> "swimming";
			case WALKING -> "walking";
			case HIKING -> "hiking";
			default -> "other";
		};
	}

	private static final class SessionData {
		boolean seen;
		String sport;
		Instant startTime;
		Double distanceM;
		Double ascentM;
		Integer elapsedS;
		Integer movingS;
		Integer avgHr;
		Integer maxHr;
		Double avgSpeedMs;
		Double maxSpeedMs;
		Integer avgCadence;
		Integer maxCadence;
		Integer avgPowerW;
		Integer maxPowerW;
	}
}
