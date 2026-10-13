import { useLocalSearchParams } from 'expo-router';
import ShelterDetailScreen from '../../screens/shelter/ShelterDetailScreen';

export default function Route() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <ShelterDetailScreen id={id} />;
}
