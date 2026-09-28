package com.travelagent.service.accommodation;

import java.util.List;

public interface AccommodationProvider {

    List<AccommodationQuote> searchAvailability(AccommodationSearchRequest request);
}
