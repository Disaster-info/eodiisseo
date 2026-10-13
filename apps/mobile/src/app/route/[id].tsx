import { useLocalSearchParams } from 'expo-router';
import RouteScreen from '../../screens/shelter/RouteScreen';

export default function Route() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <RouteScreen id={Number(id)} />;
}
