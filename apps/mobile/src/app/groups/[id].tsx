import { useLocalSearchParams } from 'expo-router';
import GroupDetailScreen from '../../screens/group/GroupDetailScreen';

export default function Route() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <GroupDetailScreen id={Number(id)} />;
}
