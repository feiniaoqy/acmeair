/*******************************************************************************
* Copyright (c) 2013 IBM Corp.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*******************************************************************************/
package com.acmeair.wxs.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import com.acmeair.entities.AirportCodeMapping;
import com.acmeair.entities.Flight;
import com.acmeair.entities.FlightPK;
import com.acmeair.entities.FlightSegment;
import com.acmeair.service.BookingService;
import com.acmeair.service.DataService;
import com.acmeair.service.FlightService;
import com.acmeair.wxs.WXSConstants;
import com.acmeair.wxs.utils.WXSSessionManager;
import com.ibm.websphere.objectgrid.ObjectGrid;
import com.ibm.websphere.objectgrid.ObjectGridException;
import com.ibm.websphere.objectgrid.ObjectMap;
import com.ibm.websphere.objectgrid.Session;

@DataService(name=WXSConstants.KEY,description=WXSConstants.KEY_DESCRIPTION)
public class FlightServiceImpl implements FlightService, WXSConstants {

	private static final String FLIGHT_MAP_NAME="Flight";
	private static final String FLIGHT_SEGMENT_MAP_NAME="FlightSegment";
	private static final String AIRPORT_CODE_MAPPING_MAP_NAME="AirportCodeMapping";
	
	private final static Logger logger = Logger.getLogger(BookingService.class.getName()); 
	
	private ObjectGrid og;
	
	@Inject
	DefaultKeyGeneratorImpl keyGenerator;
	
	//TODO: consider adding time based invalidation to these maps
	private static ConcurrentHashMap<String, FlightSegment> originAndDestPortToSegmentCache = new ConcurrentHashMap<String,FlightSegment>();
	private static ConcurrentHashMap<String, List<Flight>> flightSegmentAndDataToFlightCache = new ConcurrentHashMap<String,List<Flight>>();
	private static ConcurrentHashMap<FlightPK, Flight> flightPKtoFlightCache = new ConcurrentHashMap<FlightPK, Flight>();

	
	@PostConstruct
	private void initialization()  {	
		try {
			og = WXSSessionManager.getSessionManager().getObjectGrid();
		} catch (ObjectGridException e) {
			logger.severe("Unable to retreive the ObjectGrid reference " + e.getMessage());
		}
	}
	
	@Override
	public Long countFlights() {
		return -1L;
	}
	
	@Override
	public Long countFlightSegments() {
		// og.getSession().getMap(FLIGHT_SEGMENT_MAP_NAME)
		return -1L;
	}
	
	@Override
	public Flight getFlightByFlightKey(FlightPK key) {
		try {
			Flight flight;
			flight = flightPKtoFlightCache.get(key);
			if (flight == null) {
				//Session session = sessionManager.getObjectGridSession();
				Session session = og.getSession();
				ObjectMap flightMap = session.getMap(FLIGHT_MAP_NAME);
				HashSet<Flight> flightsBySegment = (HashSet<Flight>)flightMap.get(key.getFlightSegmentId());
				for (Flight f : flightsBySegment) {
					if (f.getPkey().getId().equals(key.getId())) {
						flightPKtoFlightCache.putIfAbsent(key, f);
						flight = f;
						break;
					}
				}
			}
			return flight;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public List<Flight> getFlightByAirportsAndDepartureDate(String fromAirport, String toAirport, Date deptDate) {
		try {
			Session session = null;
			String originPortAndDestPortQueryString = fromAirport + toAirport;
			FlightSegment segment = originAndDestPortToSegmentCache.get(originPortAndDestPortQueryString);
			boolean startedTran = false;

			if (segment == null) {
				//session = sessionManager.getObjectGridSession();
				session = og.getSession();
				if (!session.isTransactionActive()) {
					startedTran = true;
					session.begin();
				}
				ObjectMap flightSegmentMap = session.getMap(FLIGHT_SEGMENT_MAP_NAME);
				
				HashSet<FlightSegment> segmentsByOrigPort = (HashSet<FlightSegment>)flightSegmentMap.get(fromAirport);
				if (segmentsByOrigPort!=null)
				{
				for (FlightSegment fs : segmentsByOrigPort) {
					if (fs.getDestPort().equals(toAirport)) {
						segment = fs;
					}
				}
				}
				if (segment == null) {
					segment = new FlightSegment(); // put a sentinel value of a non-populated flightsegment
				}
				originAndDestPortToSegmentCache.putIfAbsent(originPortAndDestPortQueryString, segment);
			}

			// cache flights that not available (checks against sentinel value above indirectly)
			if (segment.getFlightName() == null) {
				return new ArrayList<Flight>();
			}

			String segId = segment.getFlightName();
			String flightSegmentIdAndScheduledDepartureTimeQueryString = segId + deptDate.toString();
			List<Flight> flights = flightSegmentAndDataToFlightCache.get(flightSegmentIdAndScheduledDepartureTimeQueryString);
			
			if (flights == null) {
				flights = new ArrayList<Flight>();
				if (session == null) {
					//session = sessionManager.getObjectGridSession();
					session = og.getSession();
					if (!session.isTransactionActive()) {
						startedTran = true;
						session.begin();
					}
				}				
				
				ObjectMap flightMap = session.getMap(FLIGHT_MAP_NAME);
				HashSet<Flight> flightsBySegment = (HashSet<Flight>)flightMap.get(segment.getFlightName());
				for (Flight f : flightsBySegment) {
					if (areDatesSameWithNoTime(f.getScheduledDepartureTime(), deptDate)) {
						f.setFlightSegment(segment);
						flights.add(f);
					}
				}
				
				flightSegmentAndDataToFlightCache.putIfAbsent(flightSegmentIdAndScheduledDepartureTimeQueryString, flights);
				
				if (startedTran)
					session.commit();
			}
			return flights;
			
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public static boolean areDatesSameWithNoTime(Date d1, Date d2) {
		return getDateWithNoTime(d1).equals(getDateWithNoTime(d2));
	}
	
	public static Date getDateWithNoTime(Date date) {
		Calendar c = Calendar.getInstance();
		c.setTime(date);
		c.set(Calendar.HOUR_OF_DAY, 0);
		c.set(Calendar.MINUTE, 0);
		c.set(Calendar.SECOND, 0);
		c.set(Calendar.MILLISECOND, 0);
		return c.getTime();
	}
	
	@Override
	public List<Flight> getFlightByAirports(String fromAirport, String toAirport) {
		try {
			Session session = null;
			String originPortAndDestPortQueryString = fromAirport + toAirport;
			FlightSegment segment = originAndDestPortToSegmentCache.get(originPortAndDestPortQueryString);
			boolean startedTran = false;

			if (segment == null) {				
				//session = sessionManager.getObjectGridSession();
				session = og.getSession();
				if (!session.isTransactionActive()) {
					startedTran = true;
					session.begin();
				}
				ObjectMap flightSegmentMap = session.getMap(FLIGHT_SEGMENT_MAP_NAME);
				
				HashSet<FlightSegment> segmentsByOrigPort = (HashSet<FlightSegment>)flightSegmentMap.get(fromAirport);
				for (FlightSegment fs : segmentsByOrigPort) {
					if (fs.getDestPort().equals(toAirport)) {
						segment = fs;
					}
				}
				
				if (segment == null) {
					segment = new FlightSegment(); // put a sentinel value of a non-populated flightsegment
				}
				originAndDestPortToSegmentCache.putIfAbsent(originPortAndDestPortQueryString, segment);
			}

			// cache flights that not available (checks against sentinel value above indirectly)
			if (segment.getFlightName() == null) {
				return new ArrayList<Flight>();
			}

			String segId = segment.getFlightName();
			
			ArrayList <Flight> flights = new ArrayList<Flight>();
			if (session == null) {
				//session = sessionManager.getObjectGridSession();
				session = og.getSession();
				if (!session.isTransactionActive()) {
					startedTran = true;
					session.begin();
				}
			}
	
				
			ObjectMap flightMap = session.getMap(FLIGHT_MAP_NAME);
			HashSet<Flight> flightsBySegment = (HashSet<Flight>)flightMap.get(segment.getFlightName());
			for (Flight f : flightsBySegment) {
				f.setFlightSegment(segment);
				flights.add(f);
			}
				
			if (startedTran)
				session.commit();
			return flights;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	
	
	@Override
	public void storeAirportMapping(AirportCodeMapping mapping) {
		try{
			//Session session = sessionManager.getObjectGridSession();
			Session session = og.getSession();
			ObjectMap airportCodeMappingMap = session.getMap(AIRPORT_CODE_MAPPING_MAP_NAME);
			airportCodeMappingMap.insert(mapping.getAirportCode(), mapping);
		}catch (Exception e)
		{
			throw new RuntimeException(e);
		}

		
	}

	@Override
	public Flight createNewFlight(String flightSegmentId,
			Date scheduledDepartureTime, Date scheduledArrivalTime,
			BigDecimal firstClassBaseCost, BigDecimal economyClassBaseCost,
			int numFirstClassSeats, int numEconomyClassSeats,
			String airplaneTypeId) {
		try{
			String id = keyGenerator.generate().toString();
			Flight flight = new Flight(id, flightSegmentId,
				scheduledDepartureTime, scheduledArrivalTime,
				firstClassBaseCost, economyClassBaseCost,
				numFirstClassSeats, numEconomyClassSeats,
				airplaneTypeId);
			//Session session = sessionManager.getObjectGridSession();
			Session session = og.getSession();
			ObjectMap flightMap = session.getMap(FLIGHT_MAP_NAME);
			//flightMap.insert(flight.getPkey(), flight);
			//return flight;
			HashSet<Flight> flightsBySegment = (HashSet<Flight>)flightMap.get(flightSegmentId);
			if (flightsBySegment == null) {
				flightsBySegment = new HashSet<Flight>();
			}
			if (!flightsBySegment.contains(flight)) {
				flightsBySegment.add(flight);
				flightMap.upsert(flightSegmentId, flightsBySegment);
			}
			return flight;
		}catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	@Override
	public void storeFlightSegment(FlightSegment flightSeg) {
		try {
			//Session session = sessionManager.getObjectGridSession();
			Session session = og.getSession();
			ObjectMap flightSegmentMap = session.getMap(FLIGHT_SEGMENT_MAP_NAME);
			// As the partition is on a field, the insert will not work so we use agent instead
			// flightSegmentMap.insert(flightSeg.getFlightName(), flightSeg);
			// TODO: Consider moving this to a ArrayList - List ??
			HashSet<FlightSegment> segmentsByOrigPort = (HashSet<FlightSegment>)flightSegmentMap.get(flightSeg.getOriginPort());
			if (segmentsByOrigPort == null) {
				segmentsByOrigPort = new HashSet<FlightSegment>();
			}
			if (!segmentsByOrigPort.contains(flightSeg)) {
				segmentsByOrigPort.add(flightSeg);
				flightSegmentMap.upsert(flightSeg.getOriginPort(), segmentsByOrigPort);
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
