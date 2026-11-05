import { useParams } from 'react-router';

import { useTripContext } from '../trips/tripContext';
import AddBookingSheet from './AddBookingSheet';
import { createAccommodationBooking } from './bookingsApi';
import { toInput } from './form';
import { stayDefaults, stopPoint } from './stayDefaults';

/** "Buchung hinzufügen" for an existing stop (from the stop list). */
export default function StopBookingSheet() {
  const { stopId } = useParams();
  const { trip, bookings, stopById, reload, close } = useTripContext();
  const stop = stopById.get(Number(stopId));
  return (
    <AddBookingSheet
      onClose={close}
      context={stop ? `Stop ${stop.name}` : undefined}
      defaults={stayDefaults(trip, stop, bookings)}
      near={stopPoint(stop)}
      onSubmit={async (form, source) => {
        await createAccommodationBooking(Number(stopId), toInput(form, source));
        reload();
        close();
      }}
    />
  );
}
