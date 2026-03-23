import { useQuery } from '@tanstack/react-query';
import { deviceApi } from '@/services/api';

export function useDevices() {
  return useQuery({
    queryKey: ['devices'],
    queryFn: deviceApi.list,
    refetchInterval: 3000,
  });
}

export function useDevice(imei: string) {
  return useQuery({
    queryKey: ['device', imei],
    queryFn: () => deviceApi.get(imei),
    enabled: !!imei,
    refetchInterval: 3000,
  });
}
