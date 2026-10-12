//! WINEDEBUG channel rules of the `wine_debug_channels` launch extra, mirroring the app's
//! `AgentDebugEnv.filterChannels` (which reuses `DebugRunParams`). The app drops a rejected channel
//! with a session warning and launches anyway; gnctl refuses the launch instead, before calling adb.

use crate::error::CliError;

/// `DebugRunParams.BASE_WINEDEBUG`: always set by an agent_debug launch.
pub const BASE_WINEDEBUG: &str = "warn+seh,+loaddll,+process,+timestamp,+pid,+tid";
/// `DebugRunParams.BANNED_CHANNELS`.
pub const BANNED_CHANNELS: [&str; 6] = ["all", "relay", "server", "heap", "snoop", "file"];
/// `DebugRunParams.MAX_CHANNELS`.
pub const MAX_CHANNELS: usize = 8;
/// `DebugRunParams.CHANNEL_PATTERN` (`^[+-]?[a-z0-9_]{1,24}$`) name length.
const MAX_NAME_LEN: usize = 24;

/// Reasons, named as in the app's `wine_debug_channel_dropped:<reason>:<item>` warnings.
pub const REASON_INVALID: &str = "invalid";
pub const REASON_BANNED: &str = "banned";
pub const REASON_TOO_MANY: &str = "too_many";
pub const REASON_IN_BASE: &str = "base";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Dropped {
    pub item: String,
    pub reason: &'static str,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Channels {
    pub accepted: Vec<String>,
    pub dropped: Vec<Dropped>,
}

/// `DebugRunParams.channelName`: the part after the last `+` or `-`.
pub fn channel_name(item: &str) -> &str {
    match item.rfind(['+', '-']) {
        Some(idx) => &item[idx + 1..],
        None => item,
    }
}

fn matches_pattern(item: &str) -> bool {
    let name = item.strip_prefix(['+', '-']).unwrap_or(item);
    (1..=MAX_NAME_LEN).contains(&name.len())
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

fn base_names() -> Vec<&'static str> {
    BASE_WINEDEBUG
        .split(',')
        .map(|item| channel_name(item.trim()))
        .filter(|name| !name.is_empty())
        .collect()
}

/// Same algorithm as `AgentDebugEnv.filterChannels`: split on commas, trim, lowercase, then drop
/// invalid and banned items, items whose channel the base already sets, duplicates (silently) and
/// everything past [MAX_CHANNELS]. Accepted items get a `+` when they have no sign.
pub fn filter_channels(raw: &str) -> Channels {
    let base = base_names();
    let mut channels = Channels::default();
    for item in raw
        .split(',')
        .map(|s| s.trim().to_lowercase())
        .filter(|s| !s.is_empty())
    {
        let reason = if !matches_pattern(&item) {
            Some(REASON_INVALID)
        } else if BANNED_CHANNELS.contains(&channel_name(&item)) {
            Some(REASON_BANNED)
        } else {
            None
        };
        if let Some(reason) = reason {
            channels.dropped.push(Dropped { item, reason });
            continue;
        }
        let channel = if item.starts_with(['+', '-']) {
            item.clone()
        } else {
            format!("+{item}")
        };
        let name = channel_name(&channel);
        if base.contains(&name) {
            channels.dropped.push(Dropped {
                item,
                reason: REASON_IN_BASE,
            });
            continue;
        }
        if channels
            .accepted
            .iter()
            .any(|accepted| channel_name(accepted) == name)
        {
            continue;
        }
        if channels.accepted.len() >= MAX_CHANNELS {
            channels.dropped.push(Dropped {
                item,
                reason: REASON_TOO_MANY,
            });
        } else {
            channels.accepted.push(channel);
        }
    }
    channels
}

/// The value to send as `wine_debug_channels`, or `None` when nothing is left. Any item the app
/// would drop is a usage error (exit 3), so a launch never silently loses a requested channel.
pub fn launch_value(raw: &str) -> Result<Option<String>, CliError> {
    let channels = filter_channels(raw);
    if !channels.dropped.is_empty() {
        let list: Vec<String> = channels
            .dropped
            .iter()
            .map(|d| format!("{} ({})", d.item, describe(d.reason)))
            .collect();
        return Err(CliError::Usage(format!(
            "rejected wine debug channels: {}",
            list.join(", ")
        )));
    }
    Ok(Some(channels.accepted.join(",")).filter(|s| !s.is_empty()))
}

fn describe(reason: &str) -> String {
    match reason {
        REASON_INVALID => "invalid: expected [+-]name with a-z, 0-9, _ (max 24)".into(),
        REASON_BANNED => format!("banned: {}", BANNED_CHANNELS.join(", ")),
        REASON_TOO_MANY => format!("too many: at most {MAX_CHANNELS}"),
        REASON_IN_BASE => format!("already set by the base WINEDEBUG {BASE_WINEDEBUG}"),
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dropped(item: &str, reason: &'static str) -> Dropped {
        Dropped {
            item: item.into(),
            reason,
        }
    }

    #[test]
    fn channel_name_follows_last_sign() {
        assert_eq!(channel_name("+d3d"), "d3d");
        assert_eq!(channel_name("warn+seh"), "seh");
        assert_eq!(channel_name("d3d"), "d3d");
        assert_eq!(channel_name("-fps"), "fps");
    }

    #[test]
    fn accepted_channels_are_normalized() {
        let c = filter_channels(" +D3D , vulkan,-fps,,d3d ");
        assert_eq!(c.accepted, ["+d3d", "+vulkan", "-fps"]);
        assert!(c.dropped.is_empty());
    }

    #[test]
    fn invalid_banned_and_base_channels_are_dropped() {
        let c =
            filter_channels("+all,relay,d3d!,warn+d3d,+seh,-pid,+dxgi,x234567890123456789012345");
        assert_eq!(c.accepted, ["+dxgi"]);
        assert_eq!(
            c.dropped,
            [
                dropped("+all", REASON_BANNED),
                dropped("relay", REASON_BANNED),
                dropped("d3d!", REASON_INVALID),
                dropped("warn+d3d", REASON_INVALID),
                dropped("+seh", REASON_IN_BASE),
                dropped("-pid", REASON_IN_BASE),
                dropped("x234567890123456789012345", REASON_INVALID),
            ]
        );
    }

    #[test]
    fn base_channels_do_not_take_a_slot() {
        let c = filter_channels("+seh,a,b,c,d,e,f,g,h,i");
        assert_eq!(c.accepted.len(), MAX_CHANNELS);
        assert_eq!(
            c.dropped,
            [
                dropped("+seh", REASON_IN_BASE),
                dropped("i", REASON_TOO_MANY)
            ]
        );
    }

    #[test]
    fn launch_value_rejects_any_drop() {
        assert_eq!(
            launch_value("d3d,+vulkan").unwrap().as_deref(),
            Some("+d3d,+vulkan")
        );
        assert_eq!(launch_value(" , ").unwrap(), None);
        for bad in ["+all", "heap", "+d3d,+loaddll", "a,b,c,d,e,f,g,h,i", "a b"] {
            let err = launch_value(bad).unwrap_err();
            assert_eq!(err.exit_code(), 3, "{bad}");
        }
        let err = launch_value("+all,+d3d").unwrap_err();
        assert!(err.message().contains("+all (banned"), "{}", err.message());
    }
}
