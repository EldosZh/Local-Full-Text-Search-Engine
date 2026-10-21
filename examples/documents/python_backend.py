"""A small original Python backend example using a dictionary cache."""

def get_user_by_id(user_id, user_repository):
    return user_repository.get(user_id)

# Python and Java backend services can use the same network protocol.
# Cache invalidation requires an explicit consistency policy.
