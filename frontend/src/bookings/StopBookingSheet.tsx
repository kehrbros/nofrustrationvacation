import { useParams } from 'react-router';

import { useTripContext } from '../trips/tripContext';
import AddBookingSheet from './AddBookingSheet';
import { createAccommodationBooking } from './bookingsApi';
import { toInput } from './form';

/** "Buchung hinzufügen" for an existing stop (from the stop list). */
export default function StopBookingSheet() {
  const { stopId } = useParams();
  const { stopById, reload, close } = useTripContext();
  const stop = stopById.get(Number(stopId));
  return (
    <AddBookingSheet
      onClose={close}
      context={stop ? `Stop ${stop.name}` : undefined}
      onSubmit={async (form, source) => {
        await createAccommodationBooking(Number(stopId), toInput(form, source));
        reload();
        close();
      }}
    />
  );
}
