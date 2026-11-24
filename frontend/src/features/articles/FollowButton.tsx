import { useReader } from '../../app/reader';
import type { Term } from '../../services/news';

export function FollowButton({ topic }: { topic: Term }) {
  const { following, toggleFollow } = useReader();
  const followed = following.includes(topic.slug);
  return (
    <button
      className="button"
      type="button"
      aria-label={`${followed ? 'Unfollow' : 'Follow'} ${topic.name}`}
      aria-pressed={followed}
      onClick={() => toggleFollow(topic.slug)}
    >
      {followed ? 'Following' : 'Follow'}
    </button>
  );
}
