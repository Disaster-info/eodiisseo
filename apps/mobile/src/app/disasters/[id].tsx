import { useLocalSearchParams } from 'expo-router';
import DisasterDetailScreen from '../../screens/home/DisasterDetailScreen';

export default function Route() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <DisasterDetailScreen id={Number(id)} />;
}
