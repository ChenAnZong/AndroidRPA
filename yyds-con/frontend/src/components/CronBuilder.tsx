import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Clock, ChevronDown, Zap } from 'lucide-react';

type FreqType = 'interval' | 'daily' | 'weekly' | 'monthly' | 'custom';

interface CronBuilderProps {
  value: string;
  onChange: (cron: string) => void;
}

const WEEKDAYS = [
  { key: 'mon', value: 1 },
  { key: 'tue', value: 2 },
  { key: 'wed', value: 3 },
  { key: 'thu', value: 4 },
  { key: 'fri', value: 5 },
  { key: 'sat', value: 6 },
  { key: 'sun', value: 0 },
];

const INTERVAL_OPTIONS = [
  { key: 'min1', minutes: 1 },
  { key: 'min5', minutes: 5 },
  { key: 'min10', minutes: 10 },
  { key: 'min15', minutes: 15 },
  { key: 'min30', minutes: 30 },
  { key: 'hr1', minutes: 60 },
  { key: 'hr2', minutes: 120 },
  { key: 'hr4', minutes: 240 },
  { key: 'hr6', minutes: 360 },
  { key: 'hr12', minutes: 720 },
];

const MONTH_DAYS = Array.from({ length: 31 }, (_, i) => i + 1);

function buildCron(freq: FreqType, hour: number, minute: number, weekdays: number[], intervalMin: number, monthDay: number): string {
  switch (freq) {
    case 'interval':
      if (intervalMin < 60) return `0 */${intervalMin} * * * *`;
      return `0 0 */${intervalMin / 60} * * *`;
    case 'daily':
      return `0 ${minute} ${hour} * * *`;
    case 'weekly': {
      const days = weekdays.length > 0 ? weekdays.join(',') : '*';
      return `0 ${minute} ${hour} * * ${days}`;
    }
    case 'monthly':
      return `0 ${minute} ${hour} ${monthDay} * *`;
    default:
      return '';
  }
}

function describeCron(freq: FreqType, hour: number, minute: number, weekdays: number[], intervalMin: number, monthDay: number, customExpr: string, t: (key: string, opts?: Record<string, unknown>) => string): string {
  const timeStr = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
  switch (freq) {
    case 'interval':
      if (intervalMin < 60)       return t('cron.everyMinutes', { n: intervalMin });
    return t('cron.everyHours', { n: intervalMin / 60 });
    case 'daily':
      return t('cron.dailyAt', { time: timeStr });
    case 'weekly': {
      if (weekdays.length === 0) return t('cron.selectAtLeastOneDay');
      const dayNames = [t('cron.sun'), t('cron.mon'), t('cron.tue'), t('cron.wed'), t('cron.thu'), t('cron.fri'), t('cron.sat')];
      const isWorkdays = weekdays.length === 5 && [1, 2, 3, 4, 5].every(d => weekdays.includes(d));
      const isWeekend = weekdays.length === 2 && [0, 6].every(d => weekdays.includes(d));
      const isEveryday = weekdays.length === 7;
      if (isEveryday) return t('cron.dailyAt', { time: timeStr });
      if (isWorkdays) return t('cron.workdaysAt', { time: timeStr });
      if (isWeekend) return t('cron.weekendsAt', { time: timeStr });
      const names = weekdays.sort((a, b) => a - b).map(d => dayNames[d]).join(t('cron.daySeparator'));
      return t('cron.weeklyAt', { days: names, time: timeStr });
    }
    case 'monthly':
      return t('cron.monthlyAt', { day: monthDay, time: timeStr });
    case 'custom':
      return customExpr ? t('cron.customExpr', { expr: customExpr }) : t('cron.enterCronExpr');
  }
}

function tryParseCron(expr: string): { freq: FreqType; hour: number; minute: number; weekdays: number[]; intervalMin: number; monthDay: number } | null {
  if (!expr) return null;
  const parts = expr.trim().split(/\s+/);
  if (parts.length !== 6) return null;
  const [, min, hr, dom, , dow] = parts;

  // interval: 0 */N * * * *
  if (min.startsWith('*/') && hr === '*' && dom === '*' && dow === '*') {
    const n = parseInt(min.slice(2));
    if (!isNaN(n)) return { freq: 'interval', hour: 0, minute: 0, weekdays: [], intervalMin: n, monthDay: 1 };
  }
  // interval hours: 0 0 */N * * *
  if (min === '0' && hr.startsWith('*/') && dom === '*' && dow === '*') {
    const n = parseInt(hr.slice(2));
    if (!isNaN(n)) return { freq: 'interval', hour: 0, minute: 0, weekdays: [], intervalMin: n * 60, monthDay: 1 };
  }

  const h = parseInt(hr);
  const m = parseInt(min);
  if (isNaN(h) || isNaN(m)) return null;

  // monthly: 0 M H D * *
  if (dom !== '*' && dow === '*') {
    const d = parseInt(dom);
    if (!isNaN(d)) return { freq: 'monthly', hour: h, minute: m, weekdays: [], intervalMin: 30, monthDay: d };
  }
  // weekly: 0 M H * * 1,3,5
  if (dom === '*' && dow !== '*') {
    const days = dow.split(',').map(Number).filter(n => !isNaN(n));
    if (days.length > 0) return { freq: 'weekly', hour: h, minute: m, weekdays: days, intervalMin: 30, monthDay: 1 };
  }
  // daily: 0 M H * * *
  if (dom === '*' && dow === '*') {
    return { freq: 'daily', hour: h, minute: m, weekdays: [], intervalMin: 30, monthDay: 1 };
  }
  return null;
}

export default function CronBuilder({ value, onChange }: CronBuilderProps) {
  const { t } = useTranslation(['schedule', 'common']);
  const parsed = useMemo(() => tryParseCron(value), [value]);

  const [freq, setFreq] = useState<FreqType>(parsed?.freq ?? 'daily');
  const [hour, setHour] = useState(parsed?.hour ?? 8);
  const [minute, setMinute] = useState(parsed?.minute ?? 0);
  const [weekdays, setWeekdays] = useState<number[]>(parsed?.weekdays ?? [1, 2, 3, 4, 5]);
  const [intervalMin, setIntervalMin] = useState(parsed?.intervalMin ?? 30);
  const [monthDay, setMonthDay] = useState(parsed?.monthDay ?? 1);
  const [customExpr, setCustomExpr] = useState(parsed ? '' : value);
  const [showAdvanced, setShowAdvanced] = useState(false);

  useEffect(() => {
    if (freq === 'custom') {
      if (customExpr.trim()) onChange(customExpr.trim());
      return;
    }
    const cron = buildCron(freq, hour, minute, weekdays, intervalMin, monthDay);
    if (cron && cron !== value) onChange(cron);
  }, [freq, hour, minute, weekdays, intervalMin, monthDay, customExpr]);

  const description = describeCron(freq, hour, minute, weekdays, intervalMin, monthDay, customExpr, t);
  const needsTime = freq === 'daily' || freq === 'weekly' || freq === 'monthly';

  const toggleWeekday = (day: number) => {
    setWeekdays(prev => prev.includes(day) ? prev.filter(d => d !== day) : [...prev, day]);
  };

  const quickWeekdays = (days: number[]) => setWeekdays(days);

  return (
    <div className="space-y-3">
      {/* Frequency tabs */}
      <div className="flex gap-1 p-0.5 rounded-[3px] bg-hover">
        {([
          ['interval', t('cron.freqInterval')],
          ['daily', t('cron.freqDaily')],
          ['weekly', t('cron.freqWeekly')],
          ['monthly', t('cron.freqMonthly')],
        ] as [FreqType, string][]).map(([key, label]) => (
          <button
            key={key}
            type="button"
            onClick={() => setFreq(key)}
            className={`flex-1 px-3 py-1.5 text-xs rounded-[3px] transition-all duration-150 ${
              freq === key
                ? 'bg-brand text-white shadow-sm'
                : 'text-text-muted hover:text-text-primary'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Interval selector */}
      {freq === 'interval' && (
        <div className="grid grid-cols-5 gap-1.5">
          {INTERVAL_OPTIONS.map(opt => (
            <button
              key={opt.minutes}
              type="button"
              onClick={() => setIntervalMin(opt.minutes)}
              className={`px-2 py-1.5 text-xs rounded-[3px] transition-all duration-150 ${
                intervalMin === opt.minutes
                  ? 'bg-accent-blue-bg text-brand border border-brand/30 font-medium'
                  : 'bg-hover text-text-muted hover:text-text-primary border border-transparent'
              }`}
            >
              {t(`cron.interval.${opt.key}`)}
            </button>
          ))}
        </div>
      )}

      {/* Time picker */}
      {needsTime && (
        <div className="flex items-center gap-2">
          <Clock size={14} className="text-text-hint" />
          <span className="text-xs text-text-muted">{t('cron.execTime')}</span>
          <div className="flex items-center gap-1">
            <select
              value={hour}
              onChange={e => setHour(Number(e.target.value))}
              className="input !w-16 !py-1 text-center font-mono text-sm"
            >
              {Array.from({ length: 24 }, (_, i) => (
                <option key={i} value={i}>{String(i).padStart(2, '0')}</option>
              ))}
            </select>
            <span className="text-text-muted font-bold">:</span>
            <select
              value={minute}
              onChange={e => setMinute(Number(e.target.value))}
              className="input !w-16 !py-1 text-center font-mono text-sm"
            >
              {Array.from({ length: 60 }, (_, i) => (
                <option key={i} value={i}>{String(i).padStart(2, '0')}</option>
              ))}
            </select>
          </div>
        </div>
      )}

      {/* Weekday selector */}
      {freq === 'weekly' && (
        <div className="space-y-2">
          <div className="flex items-center gap-3">
            <span className="text-xs text-text-muted">{t('cron.selectDate')}</span>
            <div className="flex gap-1 text-[10px]">
              <button type="button" onClick={() => quickWeekdays([1, 2, 3, 4, 5])}
                className="px-1.5 py-0.5 rounded-[3px] text-brand-light hover:text-brand bg-hover">{t('cron.workdays')}</button>
              <button type="button" onClick={() => quickWeekdays([0, 6])}
                className="px-1.5 py-0.5 rounded-[3px] text-brand-light hover:text-brand bg-hover">{t('cron.weekends')}</button>
              <button type="button" onClick={() => quickWeekdays([0, 1, 2, 3, 4, 5, 6])}
                className="px-1.5 py-0.5 rounded-[3px] text-brand-light hover:text-brand bg-hover">{t('cron.everyday')}</button>
            </div>
          </div>
          <div className="flex gap-1.5">
            {WEEKDAYS.map(d => (
              <button
                key={d.value}
                type="button"
                onClick={() => toggleWeekday(d.value)}
                className={`w-9 h-9 rounded-[3px] text-xs font-medium transition-all duration-150 ${
                  weekdays.includes(d.value)
                    ? 'bg-brand text-white shadow-sm'
                    : 'bg-hover text-text-muted hover:text-text-primary hover:bg-hover-strong'
                }`}
              >
                {t(`cron.${d.key}`)}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Month day selector */}
      {freq === 'monthly' && (
        <div className="space-y-2">
          <span className="text-xs text-text-muted">{t('cron.selectDate')}</span>
          <div className="grid grid-cols-10 gap-1">
            {MONTH_DAYS.map(d => (
              <button
                key={d}
                type="button"
                onClick={() => setMonthDay(d)}
                className={`h-7 rounded-[3px] text-xs transition-all duration-150 ${
                  monthDay === d
                    ? 'bg-brand text-white shadow-sm font-medium'
                    : 'bg-hover text-text-muted hover:text-text-primary hover:bg-hover-strong'
                }`}
              >
                {d}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Description + expression preview */}
      <div className="flex items-center justify-between rounded-[3px] bg-accent-blue-bg/50 px-3 py-2">
        <div className="flex items-center gap-2">
          <Zap size={13} className="text-brand" />
          <span className="text-xs text-brand font-medium">{description}</span>
        </div>
        <button
          type="button"
          onClick={() => {
            if (!showAdvanced && freq !== 'custom') {
              setCustomExpr(buildCron(freq, hour, minute, weekdays, intervalMin, monthDay));
            }
            setShowAdvanced(!showAdvanced);
          }}
          className="flex items-center gap-1 text-[10px] text-text-hint hover:text-text-muted transition-colors"
        >
          {t('cron.advanced')}
          <ChevronDown size={10} className={`transition-transform ${showAdvanced ? 'rotate-180' : ''}`} />
        </button>
      </div>

      {/* Advanced: raw cron input */}
      {showAdvanced && (
        <div className="space-y-1">
          <input
            value={freq === 'custom' ? customExpr : buildCron(freq, hour, minute, weekdays, intervalMin, monthDay)}
            onChange={e => {
              setFreq('custom');
              setCustomExpr(e.target.value);
            }}
            placeholder={t('cron.cronPlaceholder')}
            className="input font-mono text-xs"
          />
          <p className="text-[10px] text-text-hint">{t('cron.cronFormatHint')}</p>
        </div>
      )}
    </div>
  );
}
